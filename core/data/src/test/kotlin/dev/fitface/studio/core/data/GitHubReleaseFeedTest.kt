package dev.fitface.studio.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which release the update check offers, given a feed.
 *
 * The bodies here are cut down from the real response for
 * `satvikgosai/fitface-studio`, keeping the fields the parser reads and one or two it
 * ignores so `ignoreUnknownKeys` stays exercised.
 *
 * The first test is the one that matters: **every release this project publishes is a
 * prerelease**, because the workflow passes `--prerelease`. A parser that filtered those
 * out — which is what `/releases/latest` does, and why that endpoint 404s here — would
 * report that the app has no releases at all.
 */
class GitHubReleaseFeedTest {

    private fun release(
        tag: String,
        draft: Boolean = false,
        prerelease: Boolean = true,
        assets: String = asset("fitface-studio-${tag.removePrefix("v")}-debug.apk"),
    ) = """
        {
          "tag_name": "$tag",
          "draft": $draft,
          "prerelease": $prerelease,
          "created_at": "2026-08-18T14:28:01Z",
          "assets": [$assets]
        }
    """.trimIndent()

    private fun asset(
        name: String,
        size: Long = 37_922_450,
        state: String = "uploaded",
    ) = """
        {
          "name": "$name",
          "size": $size,
          "state": "$state",
          "content_type": "application/vnd.android.package-archive",
          "browser_download_url": "https://github.com/satvikgosai/fitface-studio/releases/download/v0.1.1/$name"
        }
    """.trimIndent()

    private fun feed(vararg releases: String) = releases.joinToString(prefix = "[", postfix = "]")

    @Test
    fun aFeedOfNothingButPrereleasesStillYieldsARelease() {
        val newest = GitHubReleaseFeed.newest(feed(release("v0.1.1"), release("v0.1.0")))

        assertEquals("0.1.1", newest?.version?.raw)
        assertEquals("v0.1.1", newest?.tag)
        assertEquals("fitface-studio-0.1.1-debug.apk", newest?.assetName)
        assertEquals(37_922_450L, newest?.assetBytes)
    }

    /** The API returns newest-first today, but that is not a contract. */
    @Test
    fun theNewestIsChosenByVersionNotByFeedOrder() {
        val newest = GitHubReleaseFeed.newest(
            feed(release("v0.1.0"), release("v0.1.9"), release("v0.1.10"), release("v0.1.1")),
        )

        assertEquals("0.1.10", newest?.version?.raw)
    }

    @Test
    fun aDraftIsNotOffered() {
        val newest = GitHubReleaseFeed.newest(
            feed(release("v0.2.0", draft = true), release("v0.1.1")),
        )

        assertEquals("0.1.1", newest?.version?.raw)
    }

    /** Fail closed: no place in the ordering, so it is skipped rather than guessed at. */
    @Test
    fun aPreReleaseSuffixIsSkippedRatherThanMisordered() {
        val newest = GitHubReleaseFeed.newest(
            feed(release("v0.2.0-rc1"), release("v0.1.1")),
        )

        assertEquals("0.1.1", newest?.version?.raw)
    }

    @Test
    fun aReleaseWithNoUsableAssetIsSkipped() {
        assertNull(GitHubReleaseFeed.newest(feed(release("v0.1.1", assets = ""))))
        // Source archives are on every release and are not the app.
        assertNull(
            GitHubReleaseFeed.newest(
                feed(release("v0.1.1", assets = asset("fitface-studio-0.1.1-sources.zip"))),
            ),
        )
    }

    /** GitHub lists an asset while it is still uploading, and downloading that one 404s. */
    @Test
    fun anAssetStillUploadingIsSkipped() {
        val body = feed(
            release(
                "v0.1.1",
                assets = asset("fitface-studio-0.1.1-debug.apk", state = "starter"),
            ),
        )

        assertNull(GitHubReleaseFeed.newest(body))
    }

    @Test
    fun aZeroSizedAssetIsSkippedBecauseTheSizeIsCheckedAgainstIt() {
        val body = feed(
            release("v0.1.1", assets = asset("fitface-studio-0.1.1-debug.apk", size = 0)),
        )

        assertNull(GitHubReleaseFeed.newest(body))
    }

    /** Two debug APKs on one release is a situation nobody designed; do not guess. */
    @Test
    fun anAmbiguousReleaseIsSkippedUnlessOneAssetIsNamedForIt() {
        val ambiguous = feed(
            release(
                "v0.3.0",
                assets = asset("fitface-studio-0.3.0-alpha-debug.apk") + "," +
                    asset("fitface-studio-0.3.0-beta-debug.apk"),
            ),
        )
        assertNull(GitHubReleaseFeed.newest(ambiguous))

        // ...but an exact name for the version wins over its neighbour.
        val named = feed(
            release(
                "v0.3.0",
                assets = asset("fitface-studio-0.3.0-debug.apk") + "," +
                    asset("fitface-studio-0.3.0-beta-debug.apk"),
            ),
        )
        assertEquals("fitface-studio-0.3.0-debug.apk", GitHubReleaseFeed.newest(named)?.assetName)
    }

    @Test
    fun anEmptyFeedIsNotAFailure() {
        assertNull(GitHubReleaseFeed.newest("[]"))
    }

    @Test
    fun theEndpointIsTheReleaseListNotTheLatestRelease() {
        // Pinned because `/releases/latest` 404s for this repository — every release is a
        // prerelease, and that endpoint skips them.
        assertTrue(GitHubReleaseFeed.Endpoint.endsWith("/releases?per_page=10"))
        assertFalse(GitHubReleaseFeed.Endpoint.contains("/releases/latest"))
    }
}
