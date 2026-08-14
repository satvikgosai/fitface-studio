package dev.fitface.studio.core.data

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.fitface.studio.core.model.CatalogFace
import dev.fitface.studio.core.model.FaceCatalog
import dev.fitface.studio.core.model.FaceStyleOption
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * App-private cache for the watch-face catalogue and the signed packages behind it.
 *
 * Packages are keyed by `appId` and `versionCode`, so a face is downloaded once and
 * then reused until the store publishes a new version. Older versions of the same
 * face are deleted as soon as a newer one lands.
 */
@Singleton
class PackageCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val root: File get() = File(context.filesDir, "catalog-cache")
    private val catalogFile: File get() = File(root, "catalog.json")
    private val uneditableFile: File get() = File(root, "uneditable.json")
    private val packagesDirectory: File get() = File(root, "packages")

    fun readCatalog(): FaceCatalog? {
        val cached = try {
            catalogFile.takeIf(File::isFile)
                ?.readText()
                ?.let { json.decodeFromString<CachedCatalog>(it) }
        } catch (error: Exception) {
            Log.w(TAG, "Discarding unreadable catalogue cache", error)
            catalogFile.delete()
            null
        } ?: return null
        // A cache stamped with another version is never going to be accepted again, so
        // it is deleted here rather than re-read and re-rejected on every cold start.
        if (cached.version != CacheVersion) {
            Log.w(TAG, "Discarding catalogue cache written at version ${cached.version}")
            catalogFile.delete()
            return null
        }
        return cached.toModel()
    }

    fun writeCatalog(catalog: FaceCatalog) {
        try {
            root.mkdirs()
            writeAtomically(catalogFile, json.encodeToString(CachedCatalog.of(catalog)).toByteArray())
        } catch (error: Exception) {
            Log.w(TAG, "Could not cache the catalogue", error)
        }
    }

    fun readPackage(appId: String, versionCode: Long): ByteArray? {
        val file = packageFile(appId, versionCode)
        if (!file.isFile || file.length() <= 0) return null
        return try {
            file.readBytes()
        } catch (error: IOException) {
            Log.w(TAG, "Could not read cached package $appId@$versionCode", error)
            file.delete()
            null
        }
    }

    fun writePackage(appId: String, versionCode: Long, bytes: ByteArray) {
        try {
            packagesDirectory.mkdirs()
            evictOtherVersions(appId, versionCode)
            writeAtomically(packageFile(appId, versionCode), bytes)
        } catch (error: Exception) {
            Log.w(TAG, "Could not cache package $appId@$versionCode", error)
        }
    }

    /**
     * Total bytes currently held by cached packages.
     *
     * Nothing in the app calls this yet: the storage read-out it was written for does not
     * exist, and `FaceCatalogRepository` has no method to carry the number up to a screen.
     * It is held correct by its tests until something does.
     *
     * Only committed `safeName@versionCode.apk` files count. This used to sum every file
     * in `packages/`, so an `.apk.tmp` left behind by an interrupted [writeAtomically]
     * counted as cached — bytes no `readPackage` can ever serve, which would overstate
     * any read-out by the size of a partial download.
     */
    fun cachedPackageBytes(): Long =
        packagesDirectory.listFiles().orEmpty()
            .filter { CommittedPackageName.matches(it.name) }
            .sumOf(File::length)

    fun readUneditable(): Set<String> = try {
        uneditableFile.takeIf(File::isFile)
            ?.readText()
            ?.let { json.decodeFromString<Set<String>>(it) }
            .orEmpty()
    } catch (error: Exception) {
        Log.w(TAG, "Discarding unreadable uneditable list", error)
        uneditableFile.delete()
        emptySet()
    }

    fun addUneditable(appId: String) {
        try {
            root.mkdirs()
            writeAtomically(
                uneditableFile,
                json.encodeToString(readUneditable() + appId).toByteArray(),
            )
        } catch (error: Exception) {
            Log.w(TAG, "Could not record $appId as uneditable", error)
        }
    }

    fun clear() {
        root.deleteRecursively()
    }

    /**
     * Drops every other version of `appId`. The prefix match also sees an `.apk.tmp`
     * sibling, and that is left deliberate: [writePackage] evicts *before* it opens the
     * temporary for the version it is committing, so every tmp reachable here is a write
     * nothing will ever finish — an earlier version's, or this one's from a run killed
     * before it committed.
     *
     * This pass is the only thing that reaps them. [clear] has no production caller, so
     * anything it leaves behind stays in `packages/` for the life of the install.
     */
    private fun evictOtherVersions(appId: String, keepVersion: Long) {
        val prefix = "${safeName(appId)}@"
        packagesDirectory.listFiles().orEmpty()
            .filter { it.name.startsWith(prefix) && it.name != packageFile(appId, keepVersion).name }
            .forEach { it.delete() }
    }

    private fun packageFile(appId: String, versionCode: Long): File =
        File(packagesDirectory, "${safeName(appId)}@$versionCode.apk")

    private fun safeName(appId: String): String = appId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun writeAtomically(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.outputStream().use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("Could not commit ${target.name}")
        }
    }

    private companion object {
        const val TAG = "PackageCache"

        /** Exactly what [safeName] and [packageFile] produce, and nothing half-written. */
        val CommittedPackageName = Regex("""[A-Za-z0-9._-]+@\d+\.apk""")
    }
}

/**
 * Shape of the catalogue cache on disk: bumping it invalidates every cache already
 * written.
 *
 * It is one constant because the writer and the reader have to agree by construction.
 * [CachedCatalog.of] stamped a literal `1` while [PackageCache.readCatalog] compared
 * against this, so a bump would have rejected every cache the app then wrote — the
 * catalogue would have gone permanently cold and silently, with every cold start paying
 * for the network. `internal` so a test can pin the stamp against the constant.
 */
internal const val CacheVersion = 1

@Serializable
private data class CachedCatalog(
    val version: Int,
    val fetchedAtEpochMillis: Long,
    val faces: List<CachedFace>,
) {
    fun toModel() = FaceCatalog(
        faces = faces.map(CachedFace::toModel),
        styleCount = faces.sumOf { it.styles.size },
        fetchedAtEpochMillis = fetchedAtEpochMillis,
        fromCache = true,
    )

    companion object {
        fun of(catalog: FaceCatalog) = CachedCatalog(
            version = CacheVersion,
            fetchedAtEpochMillis = catalog.fetchedAtEpochMillis,
            faces = catalog.faces.map(CachedFace::of),
        )
    }
}

@Serializable
private data class CachedFace(
    val productId: String,
    val faceId: String,
    val name: String,
    val description: String,
    val appId: String,
    val versionName: String,
    val versionCode: Long,
    val packageSize: Long,
    val styles: List<CachedStyle>,
) {
    fun toModel() = CatalogFace(
        productId = productId,
        faceId = faceId,
        name = name,
        description = description,
        appId = appId,
        versionName = versionName,
        versionCode = versionCode,
        packageSize = packageSize,
        styles = styles.map { FaceStyleOption(it.id, it.previewUrl) },
    )

    companion object {
        fun of(face: CatalogFace) = CachedFace(
            productId = face.productId,
            faceId = face.faceId,
            name = face.name,
            description = face.description,
            appId = face.appId,
            versionName = face.versionName,
            versionCode = face.versionCode,
            packageSize = face.packageSize,
            styles = face.styles.map { CachedStyle(it.id, it.previewUrl) },
        )
    }
}

@Serializable
private data class CachedStyle(val id: Int, val previewUrl: String)
