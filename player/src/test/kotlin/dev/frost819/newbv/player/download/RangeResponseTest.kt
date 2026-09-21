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
    fun `proven route stays preferred across chunks and audio representation`() {
        val original = "https://upos-sz-mirrorcosov.bilivideo.com/video?sig=v"
        val resolver = CdnResolver(ParallelDownloadConfig(mode = CdnMode.Overseas))
        resolver.success(original, 262144, 100_000_000)
        repeat(12) {
            assertEquals(
                original,
                resolver.candidates(MediaTrackSource("v", DownloadTrack.Video, listOf(original))).first(),
            )
        }
        val audio = original.replace("video?sig=v", "audio?sig=a")
        assertEquals(audio, resolver.candidates(MediaTrackSource("a", DownloadTrack.Audio, listOf(audio))).first())
        resolver.failure(original)
        kotlin.test.assertNotEquals(
            audio,
            resolver.candidates(MediaTrackSource("a", DownloadTrack.Audio, listOf(audio))).first(),
        )
    }

    @Test
    fun `API and peer CDN paths are never used as synthetic donors`() {
        listOf("api.bilivideo.com", "data.bilivideo.com", "mcdn.bilivideo.com").forEach { host ->
            val original = "https://$host/path?sig=x"
            assertEquals(
                listOf(original),
                CdnResolver(
                    ParallelDownloadConfig(),
                ).candidates(MediaTrackSource("v", DownloadTrack.Video, listOf(original))),
            )
        }
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
