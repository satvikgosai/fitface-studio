package dev.fitface.studio.core.data

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.fitface.studio.core.model.CatalogFace
import dev.fitface.studio.core.model.DiagnosticsLog
import dev.fitface.studio.core.model.DownloadProgress
import dev.fitface.studio.core.model.FaceCatalog
import dev.fitface.studio.core.model.FaceCatalogRepository
import dev.fitface.studio.core.model.FacePackage
import dev.fitface.studio.core.model.FaceStyleOption
import dev.fitface.studio.core.model.WatchFaceException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Reads the Fit3 watch-face catalogue and downloads signed face packages through
 * the same public content endpoints the stock Fit3 plugin uses.
 *
 * Both the catalogue and every downloaded package are cached on disk by
 * [PackageCache]; a package is only re-fetched when its `versionCode` changes.
 */
@Singleton
class FaceCatalogRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cache: PackageCache,
    private val diagnostics: DiagnosticsLog,
) : FaceCatalogRepository {
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override suspend fun cachedCatalog(): FaceCatalog? = withContext(Dispatchers.IO) {
        cache.readCatalog()
    }

    override suspend fun uneditableAppIds(): Set<String> = withContext(Dispatchers.IO) {
        cache.readUneditable()
    }

    override suspend fun markUneditable(appId: String) = withContext(Dispatchers.IO) {
        cache.addUneditable(appId)
    }

    override suspend fun loadCatalog(forceRefresh: Boolean): FaceCatalog =
        withContext(Dispatchers.IO) {
            if (!forceRefresh) {
                cache.readCatalog()
                    ?.takeIf { it.fetchedAtEpochMillis > System.currentTimeMillis() - CatalogTtlMillis }
                    ?.let {
                        // Recorded so a report from a warm start says why the network was
                        // never touched, rather than showing an empty log that reads as
                        // nothing having happened.
                        diagnostics.info(
                            TAG,
                            "Catalogue served from cache",
                            "faces=${it.faces.size} styles=${it.styleCount}",
                        )
                        return@withContext it
                    }
            }
            try {
                fetchCatalog()
            } catch (error: WatchFaceException) {
                // A failed refresh must not wipe a catalogue the user can still browse.
                // Every failure has to reach this guard: it used to sit after the paging
                // loop, where a throw out of the network call or the parser stepped
                // straight over it and only a page that parsed to nothing was covered.
                cache.readCatalog()?.let {
                    diagnostics.warn(TAG, "Catalogue refresh failed; keeping the cached list")
                    return@withContext it
                }
                throw error
            }
        }

    private fun fetchCatalog(): FaceCatalog {
        val faces = mutableListOf<CatalogFace>()
        var locale = catalogLocale()
        var start = 1
        var page = 0
        while (page < MaxCatalogPages) {
            val chunk = try {
                fetchCatalogPage(start, locale, allowEmpty = page > 0)
            } catch (rejection: CatalogRejected) {
                // The whitelist is not enumerable from outside, so a normalised locale is
                // not proof of an accepted one — qu_PE is well-formed and still refused.
                // Retrying is what makes this recoverable at all; normalising upstream
                // just keeps the reader's own language most of the time.
                val retry = CatalogRetry.localeAfter(rejection, locale)
                    ?: throw rejection.asWatchFaceException()
                diagnostics.warn(
                    TAG,
                    "The store refused this phone's locale; retrying in $retry",
                    "locale=$locale resultCode=${rejection.resultCode} " +
                        "message=${rejection.resultMessage}",
                )
                locale = retry
                try {
                    fetchCatalogPage(start, locale, allowEmpty = page > 0)
                } catch (retried: CatalogRejected) {
                    throw retried.asWatchFaceException()
                }
            }
            faces += chunk.faces
            page++
            if (chunk.endOfList || chunk.rawCount < PageSize) break
            start += PageSize
        }
        if (faces.isEmpty()) {
            throw WatchFaceException(
                "The catalogue returned no compatible Fit3 faces.",
                "pages=$page locale=$locale",
            )
        }
        val catalog = FaceCatalog(
            faces = faces.distinctBy(CatalogFace::productId),
            styleCount = faces.sumOf { it.styles.size },
            fetchedAtEpochMillis = System.currentTimeMillis(),
        )
        cache.writeCatalog(catalog)
        diagnostics.info(
            TAG,
            "Catalogue loaded",
            "faces=${catalog.faces.size} styles=${catalog.styleCount} locale=$locale",
        )
        return catalog
    }

    private fun fetchCatalogPage(start: Int, locale: String, allowEmpty: Boolean): CatalogPage =
        CatalogXmlParser.parseCatalogPage(
            getText(catalogUrl(start, start + PageSize - 1, locale)),
            allowEmpty = allowEmpty,
        )

    override suspend fun downloadPackage(
        face: CatalogFace,
        styleId: Int,
        onProgress: (DownloadProgress) -> Unit,
    ): FacePackage = withContext(Dispatchers.IO) {
        require(face.styles.any { it.id == styleId }) {
            "Style $styleId is not available for ${face.name}"
        }
        diagnostics.info(
            TAG,
            "Opening a face package",
            "face=${face.faceId} style=$styleId version=${face.versionCode} " +
                "size=${face.packageSize}",
        )
        // Cancellation is cooperative and the read loop below never suspends, so it has
        // to be handed the Job to ask. The updater's download learnt this first: without
        // it, cancelling cancels the coroutine and leaves the loop running to the last of
        // the bytes.
        val job = currentCoroutineContext()[Job]
        val packageBytes = cache.readPackage(face.appId, face.versionCode) ?: run {
            checkUpdate(face)
            val metadata = requestDownload(face)
            val expected = metadata.contentSize.takeIf { it > 0 } ?: face.packageSize
            downloadBoundedPackage(metadata.downloadUri, expected, job, onProgress).also {
                cache.writePackage(face.appId, face.versionCode, it)
            }
        }
        FacePackage(
            sourceKey = FacePackage.sourceKey(face.productId, face.versionCode, styleId),
            displayName = "${face.name}.apk",
            expectedFaceId = face.faceId,
            selectedStyleId = styleId,
            versionCode = face.versionCode,
            bytes = packageBytes,
        )
    }

    private fun catalogUrl(startNum: Int, endNum: Int, locale: String): HttpUrl =
        endpoint("product/getContentCategoryProductList.as")
            .newBuilder()
            .addQueryParameter("imgWidth", "216")
            .addQueryParameter("imgHeight", "432")
            .addQueryParameter("startNum", startNum.toString())
            .addQueryParameter("endNum", endNum.toString())
            .addQueryParameter("status", "1")
            .addQueryParameter("cc", CountryCode)
            .addQueryParameter("extraInfo", "screenshot")
            .addQueryParameter("callerId", PluginPackage)
            .addQueryParameter("locale", locale)
            .addQueryParameter("alignOrder", "recent")
            .addQueryParameter("contentCategoryID", ContentCategoryId)
            .addQueryParameter("mcc", mobileNetwork().first)
            .addQueryParameter("mnc", mobileNetwork().second)
            .addQueryParameter("csc", salesCode())
            .addQueryParameter("deviceId", DeviceModel)
            .addQueryParameter("sdkVer", Build.VERSION.SDK_INT.toString())
            .addQueryParameter("pd", "0")
            .build()

    private fun checkUpdate(face: CatalogFace) {
        val appInfo = "${face.appId}@${face.versionCode}"
        val url = commonStubRequest("stub/gearAppUpdateCheck.as", appInfo)
        val result = CatalogXmlParser.parseUpdateCheck(getText(url))
        if (result !in setOf(1, 2)) {
            throw WatchFaceException(
                "${face.name} is not currently available for download.",
                "update resultCode=$result",
            )
        }
    }

    private fun requestDownload(face: CatalogFace): DownloadMetadata {
        val url = commonStubRequest("stub/gearAppDownload.as", face.appId)
        val metadata = CatalogXmlParser.parseDownload(getText(url))
        if (metadata.resultCode != 1 || metadata.downloadUri.isBlank()) {
            throw WatchFaceException(
                "The store did not provide a download for ${face.name}.",
                "download resultCode=${metadata.resultCode}",
            )
        }
        val parsed = runCatching { metadata.downloadUri.toHttpUrl() }.getOrNull()
        if (parsed == null || !parsed.isHttps || !isTrustedDownloadHost(parsed.host)) {
            throw WatchFaceException(
                "The store returned an invalid package address for ${face.name}.",
                metadata.downloadUri,
            )
        }
        return metadata
    }

    private fun commonStubRequest(path: String, appInfo: String): HttpUrl {
        val (mcc, mnc) = mobileNetwork()
        return endpoint(path)
            .newBuilder()
            .addQueryParameter("csc", salesCode())
            .addQueryParameter("sdkVer", Build.VERSION.SDK_INT.toString())
            .addQueryParameter("callerId", PluginPackage)
            .addQueryParameter("versionCode", PluginVersionCode)
            .addQueryParameter("mcc", mcc)
            .addQueryParameter("mnc", mnc)
            .addQueryParameter(
                "systemId",
                (System.currentTimeMillis() - SystemClock.elapsedRealtime()).toString(),
            )
            .addQueryParameter(
                "extuk",
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                    .orEmpty(),
            )
            .addQueryParameter("abiType", abiType())
            .addQueryParameter("deviceId", DeviceModel)
            .addQueryParameter("loginType", "N")
            .addQueryParameter("oneUiVersion", platformVersion())
            .addQueryParameter("cc", CountryCode)
            .addQueryParameter("pd", "0")
            .addQueryParameter("appInfo", appInfo)
            .addQueryParameter("hashValue", storeHash(appInfo))
            .build()
    }

    private fun endpoint(path: String): HttpUrl = serverBaseUrl()
        .toHttpUrl()
        .newBuilder()
        .addPathSegments(path)
        .build()

    private fun serverBaseUrl(): String =
        if (Locale.getDefault().country.equals("CN", ignoreCase = true)) {
            "https://cn-ms.galaxyappstore.com/vas/"
        } else {
            "https://vas.samsungapps.com/"
        }

    private fun getText(url: HttpUrl): String {
        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw WatchFaceException(
                        "The watch-face catalogue could not be reached.",
                        "HTTP ${response.code} for ${url.encodedPath}",
                    )
                }
                response.body.string()
            }
        } catch (error: WatchFaceException) {
            throw error
        } catch (error: Exception) {
            throw WatchFaceException(
                "The watch-face catalogue could not be reached. Check your connection.",
                error.message,
                error,
            )
        }
    }

    private fun downloadBoundedPackage(
        url: String,
        expectedSize: Long,
        job: Job?,
        onProgress: (DownloadProgress) -> Unit,
    ): ByteArray {
        if (expectedSize > MaxPackageBytes) {
            throw WatchFaceException(
                "That watch-face package is larger than the 32 MiB safety limit.",
                "expected size=$expectedSize",
            )
        }
        val request = Request.Builder().url(url).get().build()
        val call = client.newCall(request)
        // The blocking read below cannot be interrupted from outside, so cancellation
        // reaches the socket this way and the loop itself this way too.
        job?.invokeOnCompletion { runCatching { call.cancel() } }
        return try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw WatchFaceException(
                        "The watch-face package download failed.",
                        "HTTP ${response.code}",
                    )
                }
                val finalUrl = response.request.url
                if (!finalUrl.isHttps || !isTrustedDownloadHost(finalUrl.host)) {
                    throw WatchFaceException(
                        "The download was redirected to an untrusted address.",
                        finalUrl.toString(),
                    )
                }
                val body = response.body
                val declared = body.contentLength()
                if (declared > MaxPackageBytes) {
                    throw WatchFaceException(
                        "That watch-face package is larger than the 32 MiB safety limit.",
                        "content length=$declared",
                    )
                }
                val total = declared.takeIf { it > 0 } ?: expectedSize
                val output = ByteArrayOutputStream(total.coerceIn(0, MaxPackageBytes).toInt())
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var received = 0L
                body.byteStream().use { input ->
                    while (true) {
                        if (job?.isActive == false) {
                            throw CancellationException("the package download was cancelled")
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > MaxPackageBytes) {
                            throw WatchFaceException(
                                "That watch-face package is larger than the 32 MiB safety limit.",
                            )
                        }
                        output.write(buffer, 0, count)
                        onProgress(DownloadProgress(received, total))
                    }
                }
                if (expectedSize > 0 && received != expectedSize) {
                    throw WatchFaceException(
                        "The watch-face package download was incomplete.",
                        "expected=$expectedSize actual=$received",
                    )
                }
                output.toByteArray()
            }
        } catch (error: WatchFaceException) {
            throw error
        } catch (error: CancellationException) {
            // Not a failure, and it must not be dressed up as one by the branch below —
            // which is what turns everything else into "the download failed".
            throw error
        } catch (error: Exception) {
            throw WatchFaceException(
                "The watch-face package download failed. Try again.",
                error.message,
                error,
            )
        }
    }

    private fun mobileNetwork(): Pair<String, String> {
        val operator = runCatching {
            context.getSystemService(TelephonyManager::class.java)?.networkOperator.orEmpty()
        }.getOrDefault("")
        val mcc = operator.take(3).takeIf { it.length == 3 && it.all(Char::isDigit) } ?: "450"
        val mnc = operator.drop(3).takeIf { it.length in 2..3 && it.all(Char::isDigit) } ?: "10"
        return mcc to mnc
    }

    private fun salesCode(): String = sequenceOf(
        "ro.csc.sales_code",
        "persist.omc.sales_code",
    ).mapNotNull(::readSystemProperty)
        .firstOrNull(String::isNotBlank)
        ?: "NONE"

    private fun readSystemProperty(key: String): String? = runCatching {
        val type = Class.forName("android.os.SystemProperties")
        type.getMethod("get", String::class.java).invoke(null, key) as? String
    }.getOrNull()

    private fun catalogLocale(): String =
        CatalogLocale.of(Locale.getDefault(), context.simCatalogRegion())

    private fun abiType(): String = when {
        Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() -> "64"
        Build.SUPPORTED_32_BIT_ABIS.isNotEmpty() -> "32"
        else -> "ex"
    }

    private fun platformVersion(): String = runCatching {
        Build.VERSION::class.java.getField("SEM_PLATFORM_INT").getInt(null).toString()
    }.getOrDefault("0")

    private companion object {
        const val TAG = "FaceCatalog"
        const val ContentCategoryId = "0000004252"
        const val CountryCode = "KOR"
        const val DeviceModel = "SM-R390"
        const val PluginPackage = "com.samsung.wearable.fit3plugin"
        const val PluginVersionCode = "126071051"
        const val HashSuffix = "GALAXYAPPSAPI"
        const val MaxPackageBytes = 32 * 1024 * 1024L
        const val PageSize = 100
        const val MaxCatalogPages = 20
        // A week. The store's list of faces barely moves, and pull-to-refresh is
        // right there for the reader who wants to check — so the short window only
        // bought a paged network sweep on the first launch of most days.
        const val CatalogTtlMillis = 7 * 24 * 60 * 60 * 1000L

        fun storeHash(appInfo: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(
                (appInfo + HashSuffix).toByteArray(StandardCharsets.ISO_8859_1),
            )
            return Base64.getEncoder().encodeToString(digest)
        }

        fun isTrustedDownloadHost(host: String): Boolean =
            host == "samsungapps.com" || host.endsWith(".samsungapps.com") ||
                host == "galaxyappstore.com" || host.endsWith(".galaxyappstore.com")
    }
}

internal data class DownloadMetadata(
    val resultCode: Int,
    val downloadUri: String,
    val contentSize: Long,
)

internal data class CatalogPage(
    val faces: List<CatalogFace>,
    val rawCount: Int,
    val endOfList: Boolean,
)

/**
 * The store answered a catalogue request with a non-zero `resultCode`.
 *
 * Typed so the repository can recognise [LocaleNotSupported] and retry rather than
 * matching on a message, and so the code survives into the report instead of being
 * flattened into prose the moment it reaches the UI.
 */
internal class CatalogRejected(
    val resultCode: Int?,
    val resultMessage: String,
) : Exception("catalogue resultCode=$resultCode $resultMessage") {
    fun asWatchFaceException(): WatchFaceException = WatchFaceException(
        "The watch-face catalogue did not return any faces.",
        "resultCode=$resultCode message=$resultMessage",
        this,
    )

    companion object {
        /** The pair in `locale` is not on the store's whitelist. */
        const val LocaleNotSupported = 1005

        /**
         * "No Items" — the only code that means the previous page was the last one, and
         * so the only one a follow-up page is allowed to end pagination on.
         */
        const val NoItems = 1007
    }
}

internal object CatalogXmlParser {
    private const val FaceResolution = "256x402"
    private val FaceIdPattern = Regex("sm_r390_(\\d{4,5})$", RegexOption.IGNORE_CASE)

    fun parseCatalogPage(xml: String, allowEmpty: Boolean = false): CatalogPage {
        val root = parse(xml)
        val resultCode = root.directText("resultCode").toIntOrNull()
        if (resultCode != 0) {
            // Only 1007 "No Items" means the previous page was the last one. This used
            // to accept *every* non-zero code on a follow-up page — and a null one too,
            // since a missing or unparseable element also fails `!= 0` — so a locale
            // rejection or a server error on page two read as a clean end of list. The
            // faces already collected were then cached as a successful refresh and
            // served for a week, with no CatalogRejected escaping for the locale retry
            // or the stale-cache fallback to act on. The catalogue is over one page
            // long, so page two is fetched on every cold refresh: this is the normal
            // path, not a corner of it.
            if (allowEmpty && resultCode == CatalogRejected.NoItems) {
                return CatalogPage(emptyList(), 0, endOfList = true)
            }
            throw CatalogRejected(resultCode, root.directText("resultMsg"))
        }
        val entries = root.directChildren("appInfo")
        return CatalogPage(
            faces = entries.mapNotNull(::parseFace),
            rawCount = entries.size,
            endOfList = root.directText("isEndOfList").equals("true", ignoreCase = true),
        )
    }

    fun parseUpdateCheck(xml: String): Int {
        val root = parse(xml)
        if (root.directText("resultCode").toIntOrNull() != 0) return -1
        return root.directChildren("appInfo")
            .firstOrNull()
            ?.directText("resultCode")
            ?.toIntOrNull()
            ?: -1
    }

    fun parseDownload(xml: String): DownloadMetadata {
        val root = parse(xml)
        val app = root.directChildren("appInfo").firstOrNull()
            ?: throw WatchFaceException("The store returned an invalid download response.")
        return DownloadMetadata(
            resultCode = app.directText("resultCode").toIntOrNull() ?: -1,
            downloadUri = app.directText("downloadURI"),
            contentSize = app.directText("contentSize").toLongOrNull() ?: -1,
        )
    }

    private fun parseFace(app: Element): CatalogFace? {
        val appId = app.directText("appId")
        val faceId = FaceIdPattern.find(appId)
            ?.groupValues
            ?.get(1)
            ?.padStart(5, '0')
            ?: return null
        val screenshotCount = app.directText("screenShotCount").toIntOrNull() ?: 0
        val resolutions = app.directText("screenShotResolution")
            .split('|')
            .map(String::trim)
            .filter(String::isNotEmpty)
        val shotIndices = app.directText("screenShotIndex")
            .split('|')
            .map { it.trim().toIntOrNull() }
        val base = app.directText("screenShotImgURL").removeSuffix(".png")
        if (!base.startsWith("https://")) return null
        // The catalogue mixes 256x402 face samplers with 512x512 promo art in one
        // list. Only the shots matching the watch's own aspect are style previews,
        // and their count is what lines up with the styleN.bin entries in the BIN.
        val styles = resolutions
            .take(screenshotCount.coerceAtLeast(0))
            .withIndex()
            .filter { (_, resolution) -> resolution.equals(FaceResolution, ignoreCase = true) }
            .mapIndexedNotNull { styleId, (position, resolution) ->
                val dimensions = resolution.lowercase(Locale.ROOT).split('x')
                val width = dimensions.getOrNull(0)?.toIntOrNull() ?: return@mapIndexedNotNull null
                val height = dimensions.getOrNull(1)?.toIntOrNull() ?: return@mapIndexedNotNull null
                val shot = shotIndices.getOrNull(position) ?: (position + 1)
                FaceStyleOption(
                    id = styleId,
                    previewUrl = "${base}_${width}_${height}_$shot.png",
                )
            }
        if (styles.isEmpty()) return null
        return CatalogFace(
            productId = app.directText("productID"),
            faceId = faceId,
            name = app.directText("productName").ifBlank { "Face $faceId" },
            description = app.directText("description"),
            appId = appId,
            versionName = app.directText("versionName"),
            versionCode = app.directText("versionCode").toLongOrNull() ?: return null,
            packageSize = app.directText("realContentSize").toLongOrNull() ?: -1,
            styles = styles,
        )
    }

    private fun parse(xml: String): Element {
        try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                runCatching { isXIncludeAware = false }
                setExpandEntityReferences(false)
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                runCatching {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                }
                runCatching {
                    setFeature("http://xml.org/sax/features/external-general-entities", false)
                }
                runCatching {
                    setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                }
            }
            return factory.newDocumentBuilder()
                .parse(ByteArrayInputStream(xml.toByteArray(StandardCharsets.UTF_8)))
                .documentElement
        } catch (error: Exception) {
            throw WatchFaceException(
                "The catalogue response could not be read.",
                error.message,
                error,
            )
        }
    }

    private fun Element.directChildren(tag: String): List<Element> = buildList {
        val nodes = childNodes
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node.nodeType == Node.ELEMENT_NODE && node.nodeName == tag) {
                add(node as Element)
            }
        }
    }

    private fun Element.directText(tag: String): String =
        directChildren(tag).firstOrNull()?.textContent?.trim().orEmpty()
}
