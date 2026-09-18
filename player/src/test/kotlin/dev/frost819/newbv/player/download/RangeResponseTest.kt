package dev.frost819.newbv.player.download

import org.junit.jupiter.api.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Protocol checks that prevent joining shifted or different CDN representations. */
class RangeResponseTest {
    @Test
    fun `accepts exact and final shortened range`() {
        assertEquals(100L, RangeResponse.parse("bytes 10-19/100", 10, 19, 100).total)
        assertEquals(99L, RangeResponse.parse("bytes 90-99/100", 90, 200, null).end)
    }

    @Test
    fun `rejects shifted overlong unknown or changed totals`() {
        listOf(
            "bytes 11-20/100",
            "bytes 10-20/100",
            "bytes 10-19/*",
            "bytes 10-19/101",
            "bytes 10-19/999999999999999999999999999",
        ).forEach {
            assertFailsWith<IOException> { RangeResponse.parse(it, 10, 19, 100) }
        }
    }

    @Test
    fun `pinned route preserves signed bytes without adding alternatives`() {
        val resolver = CdnResolver(ParallelDownloadConfig(mode = CdnMode.Pinned, pinnedHost = "my.example.com"))
        val routes =
            resolver.candidates(
                MediaTrackSource(
                    "v",
                    DownloadTrack.Video,
                    listOf("https://upos-sz-mirrorali.bilivideo.com/v.m4s?sig=a%2fb&x=+"),
                ),
            )
        assertEquals(listOf("https://my.example.com/v.m4s?sig=a%2fb&x=+"), routes)
    }

    @Test
    fun `akamai representation is never rewritten`() {
        val original = "https://example.akamaized.net/v.m4s?sig=x"
        val track = MediaTrackSource("v", DownloadTrack.Video, listOf(original))
        assertEquals(listOf(original), CdnResolver(ParallelDownloadConfig()).candidates(track))
        assertEquals(
            listOf("https://other.example.com/v.m4s?sig=x"),
            CdnResolver(
                ParallelDownloadConfig(mode = CdnMode.Pinned, pinnedHost = "other.example.com"),
            ).candidates(track),
        )
    }
}
