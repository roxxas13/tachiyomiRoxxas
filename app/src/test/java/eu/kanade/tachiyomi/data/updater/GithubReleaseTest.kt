package eu.kanade.tachiyomi.data.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GithubReleaseTest {
    @Test
    fun `selects APK matching primary ABI`() {
        val release =
            releaseWithAssets(
                "tachiyomij2k-v1.8.2.apk",
                "tachiyomij2k-arm64-v8a-v1.8.2.apk",
            )

        assertEquals(
            assetUrl("tachiyomij2k-arm64-v8a-v1.8.2.apk"),
            release.findDownloadLink("arm64-v8a"),
        )
    }

    @Test
    fun `falls back to universal APK`() {
        val release = releaseWithAssets("tachiyomij2k-v1.8.2.apk")

        assertEquals(assetUrl("tachiyomij2k-v1.8.2.apk"), release.findDownloadLink("x86_64"))
    }

    @Test
    fun `universal fallback does not select a different ABI`() {
        val release =
            releaseWithAssets(
                "tachiyomij2k-x86-v1.8.2.apk",
                "tachiyomij2k-v1.8.2.apk",
            )

        assertEquals(assetUrl("tachiyomij2k-v1.8.2.apk"), release.findDownloadLink("arm64-v8a"))
    }

    @Test
    fun `ignores non-APK assets`() {
        val release =
            releaseWithAssets(
                "checksums.txt",
                "release.json",
                "tachiyomij2k-v1.8.2.apk",
            )

        assertEquals(assetUrl("tachiyomij2k-v1.8.2.apk"), release.findDownloadLink("unknown"))
    }

    @Test
    fun `returns null for empty asset list`() {
        assertNull(releaseWithAssets().findDownloadLink("arm64-v8a"))
    }

    @Test
    fun `returns null when no compatible APK exists`() {
        val release = releaseWithAssets("checksums.txt", "unrelated.apk")

        assertNull(release.findDownloadLink("arm64-v8a"))
    }

    private fun releaseWithAssets(vararg names: String) =
        GithubRelease(
            version = "v1.8.2",
            info = "Notes",
            releaseLink = "https://github.com/roxxas13/tachiyomiRoxxas/releases/tag/v1.8.2",
            preRelease = false,
            assets = names.map { GithubRelease.Assets(assetUrl(it)) },
        )

    private fun assetUrl(name: String) = "https://github.com/roxxas13/tachiyomiRoxxas/releases/download/v1.8.2/$name"
}
