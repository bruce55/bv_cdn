package dev.frost819.newbv.player.download

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Memory telemetry counts backing storage, not logical slices or overlapping owners. */
class KnownDownloadStorageTest {
    @Test
    fun `shared watermark stops new work without evicting unread cached bodies`() {
        val track = MediaTrackSource("video", DownloadTrack.Video, listOf("https://media.example/video"))
        val budget = DownloadMemoryBudget(4, 0, maxHeapBytes = 64 * 1024)
        RangeBlockCache(budget.sharedLimitBytes, budget::retainCached, budget::releaseCached).use { cache ->
            val bytes = ByteArray(budget.sharedLimitBytes.toInt())
            assertTrue(cache.put(track, 0, bytes, bytes.size.toLong()))
            assertEquals(budget.sharedLimitBytes, budget.snapshot().sharedUsedBytes)
            assertFalse(budget.tryReservePayload(1))
            assertEquals(0L, cache.reclaimEligible())
            assertEquals(bytes.size.toLong(), cache.retentionStats().protectedBytes)
            cache.markDelivered(track, 0, bytes.size.toLong() - 1)
            assertEquals(0L, cache.reclaimEligible())
            cache.markDelivered(track, bytes.size.toLong() - 1, bytes.size.toLong())
            assertEquals(bytes.size.toLong(), cache.reclaimEligible(1))
            assertTrue(budget.tryReservePayload(1024))
            assertEquals(2048L, budget.snapshot().sharedUsedBytes)
            budget.releasePayload(1024)
        }
        assertEquals(0L, budget.snapshot().sharedUsedBytes)
    }

    @Test
    fun `shared cache reader and consumed references count once while equal distinct arrays count separately`() {
        val shared = ByteArray(4096)
        val separate = ByteArray(4096)
        val storage = KnownDownloadStorage()
        storage.add(shared, DownloadTrack.Video, "cache")
        storage.add(shared, DownloadTrack.Video, "reader")
        storage.add(shared, DownloadTrack.Video, "consumedCurrent")
        storage.add(separate, DownloadTrack.Audio, "attempt")
        storage.add(ByteArray(0), DownloadTrack.Audio, "attempt")
        val fields = storage.fields()
        assertEquals(8192L, fields["knownBytes"])
        assertEquals(2, fields["knownArrays"])
        for (key in listOf(
            "cacheBytes",
            "readerBytes",
            "attemptBytes",
            "videoBytes",
            "audioBytes",
            "consumedCurrentBytes",
        )) {
            assertEquals(4096L, fields[key])
        }
        assertEquals("sampled_known_arrays_excludes_transient_copies", fields["scope"])
        assertFalse(fields.values.any { it is ByteArray })
    }

    @Test
    fun `cache storage sampling preserves array identity and eviction order including pinned closed storage`() {
        val track = MediaTrackSource("video", DownloadTrack.Video, listOf("https://media.example/video"))
        val first = ByteArray(4)
        val second = ByteArray(4)
        val cache = RangeBlockCache(8)
        cache.put(track, 0, first, 100)
        cache.put(track, 4, second, 100)
        assertSame(first, cache.sampleStorage().first().second)
        cache.markDelivered(track, 0, 4)
        cache.put(track, 8, ByteArray(4), 100)
        assertFalse(cache.sampleStorage().any { it.second === first }, "Sampling must not refresh LRU recency")
        val lease = requireNotNull(cache.acquire(track, 4, 8))
        cache.close()
        assertSame(second, cache.sampleStorage().single().second)
        lease.close()
        assertEquals(emptyList(), cache.sampleStorage())
    }
}
