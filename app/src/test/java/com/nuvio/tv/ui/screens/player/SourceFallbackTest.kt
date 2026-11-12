package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceFallbackTest {

    private fun stream(
        name: String?,
        url: String? = null,
        infoHash: String? = null,
        externalUrl: String? = null,
        addonName: String = "Addon",
        description: String? = null
    ) = Stream(
        name = name,
        title = null,
        description = description,
        url = url,
        ytId = null,
        infoHash = infoHash,
        fileIdx = null,
        externalUrl = externalUrl,
        behaviorHints = null,
        addonName = addonName,
        addonLogo = null
    )

    @Test
    fun `identity keys cover link, torrent and name`() {
        val keys = sourceIdentityKeys(
            url = "https://cdn.example/a.mkv",
            infoHash = "ABCDEF",
            fileIdx = 2,
            addonName = "Addon",
            streamName = "1080p",
            description = "file.mkv"
        )
        assertEquals(
            setOf("url:https://cdn.example/a.mkv", "hash:abcdef:2", "meta:Addon|1080p|file.mkv"),
            keys
        )
        assertTrue(sourceIdentityKeys(null, null, null, null, null, null).isEmpty())
    }

    @Test
    fun `a stream without a name is known by its addon name, as the player shows it`() {
        val keys = stream(name = null, url = "https://cdn.example/b.mp4").fallbackIdentityKeys()
        assertTrue("meta:Addon|Addon|" in keys)
    }

    @Test
    fun `browser-only links are never picked automatically`() {
        assertFalse(stream("Site", externalUrl = "https://site.example/watch").isAutoFallbackCandidate())
        assertTrue(stream("Direct", url = "https://cdn.example/c.mkv").isAutoFallbackCandidate())
        assertTrue(stream("Torrent", infoHash = "0123456789abcdef0123456789abcdef01234567").isAutoFallbackCandidate())
    }

    @Test
    fun `next source skips failed ones and keeps list order`() {
        val first = stream("4K", url = "https://cdn.example/1.mkv")
        val second = stream("1080p", url = "https://cdn.example/2.mkv")
        val third = stream("720p", url = "https://cdn.example/3.mkv")
        val failed = first.fallbackIdentityKeys()

        val next = nextAutoFallbackSource(listOf(first, second, third)) { candidate ->
            candidate.fallbackIdentityKeys().any { it in failed }
        }
        assertEquals("1080p", next?.name)

        val allFailed = (first.fallbackIdentityKeys() + second.fallbackIdentityKeys() + third.fallbackIdentityKeys())
        assertNull(nextAutoFallbackSource(listOf(first, second, third)) { candidate ->
            candidate.fallbackIdentityKeys().any { it in allFailed }
        })
    }
}
