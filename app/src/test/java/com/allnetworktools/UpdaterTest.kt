package com.allnetworktools

import com.allnetworktools.update.AppUpdater
import com.allnetworktools.update.isNewerVersion
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "fr")
class UpdaterTest {
    private val updater = AppUpdater(ApplicationProvider.getApplicationContext(), "0.1")

    private fun release(tag: String, asset: String = "AllNetworkTools-$tag.apk", url: String = "https://github.com/ToftMalone/All-Radio-Tools/releases/download/$tag/$asset", pre: Boolean = false) =
        """{"tag_name":"$tag","draft":false,"prerelease":$pre,"body":"Notes","assets":[{"name":"$asset","size":4096,"browser_download_url":"$url","digest":"sha256:abc123"}]}"""

    @Test fun comparesVersionsNumerically() {
        assertTrue(isNewerVersion("v0.2", "0.1"))
        assertTrue(isNewerVersion("v0.10", "0.9"))
        assertTrue(isNewerVersion("1.0", "0.9.9"))
        assertFalse(isNewerVersion("v0.1", "0.1"))
        assertFalse(isNewerVersion("v0.1", "0.2"))
        assertFalse(isNewerVersion("v0.1.0", "0.1"))
    }

    @Test fun listsPublishedReleasesNewestFirst() {
        val json = """[{"tag_name":"v0.9.2","draft":false,"prerelease":false,"published_at":"2026-10-04T10:54:03Z","body":"- B"},""" +
            """{"tag_name":"v0.10.0","draft":false,"prerelease":false,"published_at":"2026-10-06T08:00:00Z","body":"- C"},""" +
            """{"tag_name":"v0.11.0","draft":true,"prerelease":false,"body":"- brouillon"},""" +
            """{"tag_name":"v0.9.3","draft":false,"prerelease":false,"published_at":"2026-10-04T11:43:26Z","body":"- A"}]"""
        val list = updater.parseReleases(json)
        assertEquals(listOf("v0.10.0", "v0.9.3", "v0.9.2"), list.map { it.tag })
        assertEquals("4 octobre 2026", com.allnetworktools.ui.settings.releaseDate(list[1].publishedAt))
    }

    @Test fun acceptsANewerReleaseWithAnApk() {
        val info = updater.parse(release("v0.2"))!!
        assertEquals("0.2", info.version)
        assertEquals("abc123", info.sha256)
        assertEquals(4096L, info.sizeBytes)
    }

    @Test fun ignoresOlderPreReleaseAndForeignDownloads() {
        assertNull(updater.parse(release("v0.1")))
        assertNull(updater.parse(release("v0.3", pre = true)))
        assertNull(updater.parse(release("v0.3", url = "https://evil.example.com/app.apk")))
        assertNull(updater.parse(release("v0.3", asset = "notes.txt")))
    }

    @Test fun changelogBecomesAList() {
        val items = com.allnetworktools.ui.changelogItems("## Nouveautés\r\n- Premier point\n- **Deuxième** point\n  suite du deuxième\n\n- `Troisième`")
        assertEquals(listOf("Premier point", "Deuxième point suite du deuxième", "Troisième"), items)
        assertEquals(listOf("Un paragraphe."), com.allnetworktools.ui.changelogItems("Un paragraphe."))
        assertTrue(com.allnetworktools.ui.changelogItems("").isEmpty())
    }
}
