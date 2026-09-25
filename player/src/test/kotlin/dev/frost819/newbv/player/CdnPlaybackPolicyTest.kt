package dev.frost819.newbv.player

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class CdnPlaybackPolicyTest {
    private val peer = "https://a.mcdn.bilivideo.cn/v?sign=P2P"
    private val official = "https://a.bilivideo.com/v%2Fpart?sign=A%2FB&x=+"
    private val backup = "https://b.bilivideo.com/v%2Fpart?sign=C%2BD"
    private val urls = listOf(peer, official, backup, official, "")

    @Test
    fun `parallel retains every signed candidate and never invokes preflight ranking`() =
        runTest {
            val policy = CdnPlaybackPolicy(true, "pinned.example", true)
            val result = policy.candidates(urls, rejectingSelector())
            assertThat(result).containsExactly(peer, official, backup).inOrder()
            assertThat(policy.automatic).isFalse()
        }

    @Test
    fun `manual choice precedes automatic selection without rewriting signed bytes`() =
        runTest {
            val policy = CdnPlaybackPolicy(false, "pinned.example:8443", true)
            assertThat(policy.candidates(urls, rejectingSelector())).containsExactly(
                "https://pinned.example:8443/v%2Fpart?sign=A%2FB&x=+",
            )
            assertThat(policy.automatic).isFalse()
        }

    @Test
    fun `automatic ranks official candidates and retains backups for fallback`() =
        runTest {
            val policy = CdnPlaybackPolicy(false, "", true)
            val selector =
                object : CdnSelector {
                    override suspend fun rank(urls: List<String>): List<String> {
                        assertThat(urls).containsExactly(official, backup).inOrder()
                        return urls.reversed()
                    }
                }
            assertThat(policy.candidates(urls, selector)).containsExactly(backup, official).inOrder()
            assertThat(policy.automatic).isTrue()
        }

    @Test
    fun `default needs no probes and peer-only API lists remain usable`() =
        runTest {
            val policy = CdnPlaybackPolicy(false, "", false)
            assertThat(policy.candidates(urls, rejectingSelector())).containsExactly(official)
            assertThat(policy.candidates(listOf(peer), rejectingSelector())).containsExactly(peer)
            assertThat(policy.candidates(emptyList(), rejectingSelector())).isEmpty()
        }

    @Test
    fun `filter checks hostname rather than signed query text`() {
        val signed = "https://a.bilivideo.com/v?source=.mcdn.bilivideo.cn"
        assertThat(CdnUrls.officialCandidates(listOf("https://127.0.0.1/v", signed)))
            .containsExactly(signed)
        assertThat(CdnUrls.override("https://api.bilivideo.com/x", "other.example"))
            .isEqualTo("https://api.bilivideo.com/x")
    }

    private fun rejectingSelector() =
        object : CdnSelector {
            override suspend fun rank(urls: List<String>): List<String> = error("Unexpected preflight probe")
        }
}
