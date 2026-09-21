package dev.frost819.newbv.player.download

import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Complete validated ranges remain reusable across arbitrary seeks without copying pinned bytes. */
class RangeBlockCacheTest {
    private val video = MediaTrackSource("video", DownloadTrack.Video, listOf("https://media.example/video"))

    @Test
    fun `provenance stays pending through pinned closure and repeated replay is used only once`() {
        val ledger = ExactTransferLedger()
        val source = ledger.newSource()
        source.received(8)
        val owner = source.view(0, 8)
        val cache = RangeBlockCache(8)
        val bytes = ByteArray(8)
        assertTrue(cache.put(video, 10, bytes, 100, provenance = owner))
        assertTrue(cache.put(video, 10, bytes, 100, provenance = owner))
        source.finish()
        owner.close()
        assertEquals(8L, ledger.snapshot().pendingBytes)
        val first = assertNotNull(cache.acquire(video, 12, 16))
        assertNotNull(first.provenance).delivered(first.offset.toLong(), 4)
        first.close()
        val replay = assertNotNull(cache.acquire(video, 12, 16))
        assertNotNull(replay.provenance).delivered(replay.offset.toLong(), 4)
        cache.close()
        assertEquals(4L, ledger.snapshot().usedBytes)
        assertEquals(4L, ledger.snapshot().pendingBytes)
        assertEquals(0L, ledger.snapshot().discardedBytes)
        replay.close()
        replay.close()
        assertEquals(4L, ledger.snapshot().usedBytes)
        assertEquals(0L, ledger.snapshot().pendingBytes)
        assertEquals(4L, ledger.snapshot().discardedBytes)
        assertEquals(0, ledger.snapshot().liveSourceCount)
    }

    @Test
    fun `rejected cache admission does not retain provenance and recovery eviction preserves another owner`() {
        val ledger = ExactTransferLedger()
        val source = ledger.newSource()
        source.received(4)
        val owner = source.view(0, 4)
        val bytes = ByteArray(4)
        RangeBlockCache(4, reserve = { false }).use { rejected ->
            assertFalse(rejected.put(video, 10, bytes, 100, provenance = owner))
        }
        RangeBlockCache(4).use { cache ->
            assertTrue(cache.put(video, 10, bytes, 100, provenance = owner))
            source.finish()
            assertEquals(4L, cache.evictUnread(video, 10, 14))
            assertEquals(4L, ledger.snapshot().pendingBytes)
            assertEquals(0L, ledger.snapshot().discardedBytes)
            owner.delivered(0, 2)
            owner.close()
            assertEquals(2L, ledger.snapshot().usedBytes)
            assertEquals(2L, ledger.snapshot().discardedBytes)
            assertEquals(0L, ledger.snapshot().pendingBytes)
            assertEquals(0, ledger.snapshot().liveSourceCount)
        }
    }

    @Test
    fun `explicit recovery evicts unread entries but preserves pinned partial and other track ranges`() {
        val released = mutableListOf<ByteArray>()
        val cache = RangeBlockCache(32, release = { released.add(it) })
        val audio = video.copy(id = "audio", kind = DownloadTrack.Audio)
        val pinnedBytes = ByteArray(4) { 1 }
        val victimBytes = ByteArray(4) { 2 }
        assertTrue(cache.put(video, 10, pinnedBytes, 100))
        assertTrue(cache.put(video, 20, victimBytes, 100))
        assertTrue(cache.put(video, 28, ByteArray(4), 100))
        assertTrue(cache.put(audio, 20, ByteArray(4), 100))
        val beforePin = cache.evictionCandidates()
        val lease = assertNotNull(cache.acquire(video, 10, 14))
        assertFalse(beforePin.first { it.start == 10L }.pinned)
        assertTrue(cache.evictionCandidates().first { it.start == 10L }.pinned)
        assertEquals(0L, cache.reclaimEligible())
        assertEquals(4L, cache.evictUnread(video, 10, 30))
        assertEquals(1, released.size)
        assertSame(victimBytes, released.single())
        assertEquals(12L, cache.usedBytes)
        assertNull(cache.acquire(video, 20, 24))
        assertNotNull(cache.acquire(video, 28, 32)).close()
        assertNotNull(cache.acquire(audio, 20, 24)).close()
        assertSame(pinnedBytes, lease.bytes)
        assertEquals(0L, cache.evictUnread(video, 10, 14))
        lease.close()
        assertEquals(4L, cache.evictUnread(video, 10, 14))
        assertEquals(0L, cache.evictUnread(video, 10, 14))
        cache.close()
        assertEquals(4, released.size)
        assertTrue(cache.evictionCandidates().isEmpty())
        assertEquals(0L, cache.evictUnread(video, 0, 100))
    }

    @Test
    fun `recovery eviction releases each cache ownership even when backing array is shared`() {
        var ownerships = 0
        val shared = ByteArray(4)
        RangeBlockCache(
            8,
            reserve = {
                ownerships++
                true
            },
            release = { ownerships-- },
        ).use { cache ->
            assertTrue(cache.put(video, 10, shared, 100))
            assertTrue(cache.put(video, 20, shared, 100))
            assertEquals(2, ownerships)
            val lease = assertNotNull(cache.acquire(video, 20, 24))
            assertEquals(4L, cache.evictUnread(video, 10, 24))
            assertEquals(1, ownerships)
            assertSame(shared, lease.bytes)
            assertEquals(0L, cache.evictUnread(video, -1, 100))
            assertEquals(0L, cache.evictUnread(video, 24, 20))
            lease.close()
            assertEquals(4L, cache.evictUnread(video, 20, 24))
            assertEquals(0, ownerships)
        }
        assertEquals(0, ownerships)
    }

    @Test
    fun `close can abandon completion inserted after seek before any new reader acquires it`() {
        RangeBlockCache(8).use { cache ->
            val bytes = ByteArray(4)
            cache.abandonUnread()
            assertTrue(cache.put(video, 10, bytes, 100))
            assertEquals(4L, cache.retentionStats().protectedBytes)
            assertTrue(cache.put(video, 10, bytes, 100, abandoned = true))
            assertEquals(4L, cache.reclaimEligible())
            assertTrue(cache.ranges().isEmpty())
        }
    }

    @Test
    fun `obsolete late completion is reclaimable but repeated insertion cannot abandon a reused entry`() {
        RangeBlockCache(8).use { cache ->
            val bytes = ByteArray(4)
            cache.abandonUnread()
            assertTrue(cache.put(video, 10, bytes, 100, abandoned = true))
            assertEquals(4L, cache.retentionStats().evictableBytes)
            assertNotNull(cache.acquire(video, 10, 14)).close()
            assertTrue(cache.put(video, 10, bytes, 100, abandoned = true))
            assertEquals(4L, cache.retentionStats().protectedBytes)
            assertEquals(0L, cache.reclaimEligible())
            assertTrue(cache.put(video, 20, ByteArray(4), 100, abandoned = true))
            assertEquals(4L, cache.reclaimEligible())
            assertEquals(listOf(10L), cache.ranges().map { it.start })
        }
    }

    @Test
    fun `retention counters deduplicate shared arrays and pinned subset stays protected`() {
        RangeBlockCache(12).use { cache ->
            val shared = ByteArray(4)
            assertTrue(cache.put(video, 0, shared, 100))
            assertTrue(cache.put(video, 10, shared, 100, abandoned = true))
            assertTrue(cache.put(video, 20, ByteArray(4), 100, abandoned = true))
            assertEquals(RangeBlockCache.RetentionStats(4, 4, 0), cache.retentionStats())
            val lease = assertNotNull(cache.acquire(video, 0, 4))
            cache.markDelivered(video, 0, 4)
            assertEquals(RangeBlockCache.RetentionStats(4, 4, 4), cache.retentionStats())
            lease.close()
            assertEquals(RangeBlockCache.RetentionStats(0, 8, 0), cache.retentionStats())
            cache.reclaimEligible()
            assertEquals(RangeBlockCache.RetentionStats(0, 0, 0), cache.retentionStats())
        }
    }

    @Test
    fun `unread cache remains protected even when a nearer candidate arrives`() {
        RangeBlockCache(8).use { cache ->
            cache.setReadPosition(video, 90)
            assertTrue(cache.put(video, 100, ByteArray(4), 1000))
            assertTrue(cache.put(video, 200, ByteArray(4), 1000))
            val original = cache.ranges()
            assertFalse(cache.put(video, 90, ByteArray(4), 1000))
            assertEquals(0L, cache.reclaimEligible())
            assertEquals(original, cache.ranges())
            cache.markDelivered(video, 100, 104)
            assertTrue(cache.put(video, 90, ByteArray(4), 1000))
            assertEquals(setOf(90L, 200L), cache.ranges().map { it.start }.toSet())
        }
    }

    @Test
    fun `delivery coverage must include the whole array and seek anchor is not delivery`() {
        RangeBlockCache(8).use { cache ->
            assertTrue(cache.put(video, 100, ByteArray(8), 1000))
            cache.setReadPosition(video, 900)
            assertEquals(0L, cache.reclaimEligible())
            cache.markDelivered(video, 100, 103)
            cache.markDelivered(video, 105, 108)
            assertEquals(0L, cache.reclaimEligible())
            cache.markDelivered(video, 103, 105)
            assertEquals(8L, cache.reclaimEligible(1))
            assertEquals(0, cache.entryCount)
        }
    }

    @Test
    fun `abandonment is explicit and reusing a selected range restores protection`() {
        RangeBlockCache(12).use { cache ->
            assertTrue(cache.put(video, 0, ByteArray(4), 100))
            assertTrue(cache.put(video, 10, ByteArray(4), 100))
            assertTrue(cache.put(video, 20, ByteArray(4), 100))
            val oldPinned = assertNotNull(cache.acquire(video, 0, 4))
            cache.abandonUnread()
            assertNotNull(cache.acquire(video, 10, 14)).close()
            assertEquals(4L, cache.reclaimEligible())
            assertEquals(setOf(0L, 10L), cache.ranges().map { it.start }.toSet())
            oldPinned.close()
            assertEquals(4L, cache.reclaimEligible())
            assertEquals(listOf(10L), cache.ranges().map { it.start })
        }
    }

    @Test
    fun `delivered before cache admission is reclaimable and callback ownership survives pins`() {
        val retained = mutableListOf<ByteArray>()
        val released = mutableListOf<ByteArray>()
        val cache =
            RangeBlockCache(8, reserve = {
                retained.add(it)
                true
            }, release = { released.add(it) })
        val bytes = ByteArray(4)
        cache.markDelivered(video, 0, 4)
        assertTrue(cache.put(video, 0, bytes, 100))
        assertTrue(cache.put(video, 0, bytes, 100))
        val lease = assertNotNull(cache.acquire(video, 0, 4))
        assertEquals(0L, cache.reclaimEligible())
        cache.close()
        assertEquals(listOf(bytes), retained)
        assertTrue(released.isEmpty())
        lease.close()
        lease.close()
        assertEquals(listOf(bytes), released)
    }

    @Test
    fun `shared admission rejection does not discard existing eligible data`() {
        var accept = true
        var releases = 0
        RangeBlockCache(4, reserve = { accept }, release = { releases++ }).use { cache ->
            assertTrue(cache.put(video, 0, ByteArray(4), 100))
            cache.markDelivered(video, 0, 4)
            accept = false
            assertFalse(cache.put(video, 10, ByteArray(4), 100))
            assertEquals(listOf(0L), cache.ranges().map { it.start })
            assertEquals(0, releases)
        }
        assertEquals(1, releases)
    }

    @Test
    fun `same start shorter replacement cannot discard an unread suffix`() {
        RangeBlockCache(8).use { cache ->
            assertTrue(cache.put(video, 0, ByteArray(8), 100))
            assertFalse(cache.put(video, 0, ByteArray(4), 100))
            cache.markDelivered(video, 0, 8)
            assertTrue(cache.put(video, 0, ByteArray(4), 100))
            assertEquals(4L, cache.usedBytes)
        }
    }

    @Test
    fun `range snapshots reflect eviction and pinned entries without retaining old blocks`() {
        RangeBlockCache(8).use { cache ->
            cache.markDelivered(video, 0, 100)
            assertTrue(cache.ranges().isEmpty())
            assertTrue(cache.put(video, 0, ByteArray(4), 100))
            assertTrue(cache.put(video, 10, ByteArray(4), 100))
            val pinned = assertNotNull(cache.acquire(video, 0, 100))
            val beforeEviction = cache.ranges()
            assertEquals(
                listOf(RangeBlockCache.Range(video, 0, 4), RangeBlockCache.Range(video, 10, 14)),
                beforeEviction,
            )
            assertTrue(cache.put(video, 20, ByteArray(4), 100))
            assertEquals(
                listOf(RangeBlockCache.Range(video, 0, 4), RangeBlockCache.Range(video, 20, 24)),
                cache.ranges(),
            )
            assertEquals(10L, beforeEviction.last().start)
            pinned.close()
            assertTrue(cache.put(video, 30, ByteArray(4), 100))
            assertEquals(
                listOf(RangeBlockCache.Range(video, 20, 24), RangeBlockCache.Range(video, 30, 34)),
                cache.ranges(),
            )
        }
    }

    @Test
    fun `range snapshots distinguish representations and exclude closed pinned storage`() {
        val cache = RangeBlockCache(12)
        val audio = video.copy(id = "audio", kind = DownloadTrack.Audio)
        val otherQuality = video.copy(id = "video4k")
        assertTrue(cache.put(video, 0, ByteArray(4), 100))
        assertTrue(cache.put(audio, 0, ByteArray(4), 100))
        assertTrue(cache.put(otherQuality, 0, ByteArray(4), 100))
        assertEquals(setOf(video, audio, otherQuality), cache.ranges().map { it.track }.toSet())
        val lease = assertNotNull(cache.acquire(video, 0, 100))
        cache.close()
        assertEquals(4L, cache.usedBytes)
        assertTrue(cache.ranges().isEmpty())
        lease.close()
        assertEquals(0L, cache.usedBytes)
        assertTrue(cache.ranges().isEmpty())
    }

    @Test
    fun `unaligned lookup clips metadata while retaining the original backing array`() {
        RangeBlockCache(32).use { cache ->
            val bytes = byteArrayOf(10, 11, 12, 13, 14, 15)
            assertTrue(cache.put(video, 100, bytes, 200))
            assertNotNull(cache.acquire(video, 102, 105)).use { lease ->
                assertSame(bytes, lease.bytes)
                assertEquals(100L, lease.blockStart)
                assertEquals(102L, lease.start)
                assertEquals(105L, lease.endExclusive)
                assertEquals(200L, lease.total)
                assertEquals(2, lease.offset)
                assertContentEquals(byteArrayOf(12, 13, 14), lease.bytes.copyOfRange(lease.offset, 5))
            }
            assertEquals(6L, cache.usedBytes)
            assertEquals(1, cache.entryCount)
        }
    }

    @Test
    fun `adjacent blocks gaps and overlapping entries choose the farthest useful range`() {
        RangeBlockCache(100).use { cache ->
            assertTrue(cache.put(video, 0, ByteArray(10), 100))
            assertTrue(cache.put(video, 10, ByteArray(10), 100))
            assertTrue(cache.put(video, 15, ByteArray(20), 100))
            assertTrue(cache.put(video, 50, ByteArray(5), 100))
            assertNotNull(cache.acquire(video, 10, 100)).use { assertEquals(20L, it.endExclusive) }
            assertNotNull(cache.acquire(video, 16, 100)).use { assertEquals(35L, it.endExclusive) }
            assertNull(cache.acquire(video, 35, 100))
            assertEquals(50L, cache.nextStart(video, 35))
            assertEquals(15L, cache.nextStart(video, 10))
            assertNull(cache.nextStart(video, 50))
        }
    }

    @Test
    fun `LRU evicts only unpinned entries and rejected admission leaves existing bytes intact`() {
        RangeBlockCache(8).use { cache ->
            cache.markDelivered(video, 0, 100)
            assertTrue(cache.put(video, 0, ByteArray(4) { 1 }, 100))
            assertTrue(cache.put(video, 10, ByteArray(4) { 2 }, 100))
            val pinned = assertNotNull(cache.acquire(video, 0, 100))
            assertTrue(cache.put(video, 20, ByteArray(4) { 3 }, 100))
            assertNull(cache.acquire(video, 10, 100))
            val otherPinned = assertNotNull(cache.acquire(video, 20, 100))
            assertFalse(cache.put(video, 30, ByteArray(1), 100))
            assertFalse(cache.put(video, 0, ByteArray(2), 100))
            assertEquals(8L, cache.usedBytes)
            assertContentEquals(ByteArray(4) { 1 }, pinned.bytes)
            pinned.close()
            pinned.close()
            assertTrue(cache.put(video, 30, ByteArray(4), 100))
            assertNull(cache.acquire(video, 0, 100))
            assertContentEquals(ByteArray(4) { 3 }, otherPinned.bytes)
            otherPinned.close()
        }
    }

    @Test
    fun `recent access determines eviction and exact start replacement adjusts budget`() {
        RangeBlockCache(8).use { cache ->
            cache.markDelivered(video, 0, 100)
            assertTrue(cache.put(video, 0, ByteArray(4), 100))
            assertTrue(cache.put(video, 10, ByteArray(4), 100))
            assertNotNull(cache.acquire(video, 0, 100)).close()
            assertTrue(cache.put(video, 20, ByteArray(4), 100))
            assertNull(cache.acquire(video, 10, 100))
            assertTrue(cache.put(video, 20, ByteArray(2), 100))
            assertEquals(6L, cache.usedBytes)
            assertEquals(2, cache.entryCount)
            assertNotNull(cache.acquire(video, 20, 100)).use { assertEquals(22L, it.endExclusive) }
        }
    }

    @Test
    fun `representations remain separate and invalid bounds or conflicting totals are rejected`() {
        RangeBlockCache(16).use { cache ->
            assertTrue(cache.put(video, 0, ByteArray(4), 100))
            val audio = video.copy(kind = DownloadTrack.Audio)
            val quality = video.copy(id = "video4k")
            val changedUrl = video.copy(urls = listOf("https://media.example/other"))
            listOf(audio, quality, changedUrl).forEach {
                assertNull(cache.acquire(it, 0, 100))
                assertNull(cache.nextStart(it, 0))
            }
            assertFalse(cache.put(video, 4, ByteArray(1), 101))
            assertFalse(cache.put(video, -1, ByteArray(1), 100))
            assertFalse(cache.put(video, 0, ByteArray(0), 100))
            assertFalse(cache.put(video, 99, ByteArray(2), 100))
            assertFalse(cache.put(video, Long.MAX_VALUE, ByteArray(1), Long.MAX_VALUE))
            assertFalse(cache.put(video, 0, ByteArray(17), 100))
            assertNull(cache.acquire(video, 0, 0))
            assertNull(cache.acquire(video, -1, 10))
            assertEquals(4L, cache.usedBytes)
        }
    }

    @Test
    fun `close drops unpinned entries but pinned leases stay valid and release exactly once`() {
        val cache = RangeBlockCache(8)
        val bytes = ByteArray(4) { 7 }
        assertTrue(cache.put(video, 0, bytes, 100))
        assertTrue(cache.put(video, 10, ByteArray(4), 100))
        val first = assertNotNull(cache.acquire(video, 1, 3))
        val second = assertNotNull(cache.acquire(video, 2, 4))
        cache.close()
        cache.close()
        assertEquals(4L, cache.usedBytes)
        assertEquals(1, cache.entryCount)
        assertSame(bytes, first.bytes)
        assertSame(bytes, second.bytes)
        assertNull(cache.acquire(video, 1, 3))
        assertNull(cache.nextStart(video, 0))
        assertFalse(cache.put(video, 20, ByteArray(1), 100))
        first.close()
        first.close()
        assertEquals(4L, cache.usedBytes)
        second.close()
        second.close()
        assertEquals(0L, cache.usedBytes)
        assertEquals(0, cache.entryCount)
    }
}
