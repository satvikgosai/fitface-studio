package dev.fitface.studio.core.data

import dev.fitface.studio.core.model.AppRelease
import dev.fitface.studio.core.model.AppVersion
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Turns the releases feed into the newest installable release, and nothing else.
 *
 * A pure parser over the response body, the same seam `CatalogXmlParser` uses: there is
 * no MockWebServer in this project's version catalogue and no HTTP test anywhere, so
 * "given this body, which release" is the part that can actually be pinned by a test.
 */
internal object GitHubReleaseFeed {

    /**
     * The endpoint. **`/releases`, never `/releases/latest`.**
     *
     * `/releases/latest` returns **404** for this repository, because the release
     * workflow publishes every build with `gh release create --prerelease` and that
     * endpoint skips prereleases. Every release there is has ever been a prerelease, so
     * the "obvious" endpoint reports that the app has no releases at all. Do not
     * simplify this back.
     */
    const val Endpoint = "https://api.github.com/repos/satvikgosai/fitface-studio/releases?per_page=10"

    /**
     * The asset the release workflow produces: it renames `app-debug.apk` to
     * `fitface-studio-<version>-debug.apk` before uploading, so the name is predictable.
     */
    private val AssetName = Regex("""^fitface-studio-.+-debug\.apk$""")

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The newest release worth offering, or null when the feed holds none.
     *
     * Newest is decided by comparing parsed versions, **not** by taking the first
     * element. The API happens to return newest-created first, but that is not a
     * contract and a re-cut tag would break it.
     */
    fun newest(body: String): AppRelease? = parse(body).maxByOrNull(AppRelease::version)

    /** Every usable release in the feed, in no particular order. */
    fun parse(body: String): List<AppRelease> =
        json.decodeFromString<List<ReleaseDto>>(body).mapNotNull(::toRelease)

    private fun toRelease(dto: ReleaseDto): AppRelease? {
        // A draft is not published; a prerelease is the only kind this project ships, so
        // filtering those out would leave nothing.
        if (dto.draft) return null
        val version = AppVersion.parse(dto.tagName) ?: return null
        val asset = pickAsset(dto, version) ?: return null
        if (asset.size <= 0) return null
        return AppRelease(
            version = version,
            tag = dto.tagName,
            assetName = asset.name,
            assetUrl = asset.browserDownloadUrl,
            assetBytes = asset.size,
        )
    }

    /**
     * The asset named for this exact version, else the single debug APK on the release.
     *
     * "Exactly one" rather than "the first": a release carrying two APKs is a situation
     * nobody has thought about, and picking one arbitrarily would install whichever
     * happened to be listed first.
     *
     * `state` must be `uploaded` — GitHub lists an asset while it is still uploading and
     * downloading that one 404s.
     */
    private fun pickAsset(dto: ReleaseDto, version: AppVersion): AssetDto? {
        val usable = dto.assets.filter {
            it.state == "uploaded" && it.browserDownloadUrl.isNotBlank() && AssetName.matches(it.name)
        }
        val exact = "fitface-studio-${version.raw}-debug.apk"
        return usable.firstOrNull { it.name == exact } ?: usable.singleOrNull()
    }
}

@Serializable
internal data class ReleaseDto(
    @SerialName("tag_name") val tagName: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<AssetDto> = emptyList(),
)

@Serializable
internal data class AssetDto(
    val name: String = "",
    val size: Long = 0,
    val state: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)

/**
 * Where an update may be fetched from.
 *
 * Two hosts, because a release asset is served by redirect: `github.com` hands out a 302
 * to a `*.githubusercontent.com` origin. Checked on the URL from the feed and **again**
 * on `response.request.url` after the redirects, which is the discipline the face-package
 * download already follows.
 *
 * The suffix tests are on `.githubusercontent.com` with the dot included on purpose:
 * `endsWith("githubusercontent.com")` would also accept
 * `githubusercontent.com.example.net`.
 */
internal fun isTrustedUpdateHost(host: String): Boolean =
    host == "api.github.com" ||
        host == "github.com" ||
        host == "githubusercontent.com" ||
        host.endsWith(".githubusercontent.com")
