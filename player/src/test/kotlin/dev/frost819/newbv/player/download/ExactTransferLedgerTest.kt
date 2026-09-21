package dev.frost819.newbv.player.download

import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExactTransferLedgerTest {
    @Test
    fun `sequential delivery needs no retained metadata`() {
        val ledger = ExactTransferLedger()
        ledger.deliveredDirect(100)
        assertPartition(ledger, 100, 100, 0, 0)
        assertEquals(0, ledger.snapshot().liveSourceCount)
        assertEquals(0, ledger.snapshot().liveRangeCount)
    }

    @Test
    fun `cached replay counts once but downloading the same media again counts independently`() {
        val ledger = ExactTransferLedger()
        val source = ledger.newSource()
        source.received(100)
        val cache = source.view(0, 100)
        source.finish()
        cache.delivered(0, 60)
        cache.delivered(20, 80)
        val replay = cache.slice(0, 100)
        replay.delivered(0, 100)
        replay.close()
        cache.close()
        assertPartition(ledger, 100, 100, 0, 0)
        val second = ledger.newSource()
        second.received(100)
        val fresh = second.view(0, 100)
        fresh.delivered(0, 100)
        second.finish()
        fresh.close()
        assertPartition(ledger, 200, 200, 0, 0)
        assertEquals(0, ledger.snapshot().liveSourceCount)
    }

    @Test
    fun `stitched prefix survives losing source and duplicated overlap is discarded`() {
        val ledger = ExactTransferLedger()
        val original = ledger.newSource()
        original.received(80)
        val prefix = original.view(0, 60)
        val rescue = ledger.newSource()
        rescue.received(60)
        val suffix = rescue.view(20, 40)
        val stitched = ledger.concat(listOf(prefix, suffix))
        original.finish(DownloadOverheadKind.Rescue)
        rescue.finish(DownloadOverheadKind.Rescue)
        prefix.close()
        suffix.close()
        assertPartition(ledger, 140, 0, 100, 40)
        stitched.delivered(0, 100)
        stitched.close()
        assertPartition(ledger, 140, 100, 0, 40)
        assertEquals(40L, ledger.snapshot().discardedByKind[DownloadOverheadKind.Rescue])
    }

    @Test
    fun `partial failure preserves copied prefix and never counts missing expected bytes`() {
        val ledger = ExactTransferLedger()
        val source = ledger.newSource()
        source.received(70)
        val prefix = source.view(0, 30)
        source.finish(DownloadOverheadKind.Failure)
        assertPartition(ledger, 70, 0, 30, 40)
        prefix.delivered(0, 10)
        prefix.close()
        assertPartition(ledger, 70, 10, 0, 60)
        assertEquals(60L, ledger.snapshot().discardedByKind[DownloadOverheadKind.Failure])
    }

    @Test
    fun `overlapping views use source identity and close idempotently`() {
        val ledger = ExactTransferLedger()
        val source = ledger.newSource()
        source.received(100)
        val first = source.view(0, 80)
        val second = source.view(20, 80)
        source.finish()
        first.delivered(0, 60)
        first.close(DownloadOverheadKind.Cancelled)
        first.close(DownloadOverheadKind.Failure)
        source.finish(DownloadOverheadKind.Failure)
        assertPartition(ledger, 100, 60, 40, 0)
        second.delivered(20, 30)
        second.close(DownloadOverheadKind.Exploration)
        assertPartition(ledger, 100, 70, 0, 30)
        assertEquals(30L, ledger.snapshot().discardedByKind[DownloadOverheadKind.Exploration])
    }

    @Test
    fun `pending source ownership prevents premature disposal when a view closes`() {
        val ledger = ExactTransferLedger()
        val source = ledger.newSource()
        source.received(100)
        val temporary = source.view(0, 100)
        temporary.close()
        assertPartition(ledger, 100, 0, 100, 0)
        val final = source.view(0, 100)
        source.finish(DownloadOverheadKind.Probe)
        final.close()
        assertPartition(ledger, 100, 0, 0, 100)
        assertEquals(100L, ledger.snapshot().discardedByKind[DownloadOverheadKind.Probe])
    }

    @Test
    fun `bounds and closed owners cannot resurrect discarded data`() {
        val ledger = ExactTransferLedger()
        val source = ledger.newSource()
        source.received(100)
        assertFailsWith<IllegalArgumentException> { source.view(80, 21) }
        val handle = source.view(0, 100)
        assertFailsWith<IllegalArgumentException> { handle.slice(Long.MAX_VALUE, 1) }
        source.finish()
        handle.close()
        assertFailsWith<IllegalStateException> { source.view(0, 100) }
        assertFailsWith<IllegalStateException> { handle.slice(0, 100) }
        assertFailsWith<IllegalStateException> { handle.delivered(0, 100) }
        assertPartition(ledger, 100, 0, 0, 100)
    }

    @Test
    fun `concurrent replay and release never duplicate counters`() {
        val ledger = ExactTransferLedger()
        val source = ledger.newSource()
        source.received(1024)
        val cache = source.view(0, 1024)
        val views = List(16) { cache.slice(0, 1024) }
        source.finish()
        cache.close()
        val executor = Executors.newFixedThreadPool(4)
        try {
            executor
                .invokeAll(
                    views.map { view ->
                        Callable {
                            view.delivered(0, 1024)
                            view.close()
                            view.close()
                        }
                    },
                ).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
        assertPartition(ledger, 1024, 1024, 0, 0)
        assertEquals(0, ledger.snapshot().liveRangeCount)
    }

    @Test
    fun `stream reads merge metadata and finalized history does not accumulate`() {
        val ledger = ExactTransferLedger()
        repeat(2000) {
            val source = ledger.newSource()
            repeat(32) { source.received(8192) }
            assertEquals(1, ledger.snapshot().liveRangeCount)
            val cache = source.view(0, 262144)
            source.finish()
            repeat(32) { cache.delivered(it * 8192L, 8192) }
            assertEquals(1, ledger.snapshot().liveRangeCount)
            cache.close()
            assertEquals(0, ledger.snapshot().liveSourceCount)
            assertEquals(0, ledger.snapshot().liveRangeCount)
        }
        assertPartition(ledger, 524288000, 524288000, 0, 0)
    }

    private fun assertPartition(
        ledger: ExactTransferLedger,
        downloaded: Long,
        used: Long,
        pending: Long,
        discarded: Long,
    ) {
        val snapshot = ledger.snapshot()
        assertEquals(downloaded, snapshot.downloadedBytes)
        assertEquals(used, snapshot.usedBytes)
        assertEquals(pending, snapshot.pendingBytes)
        assertEquals(discarded, snapshot.discardedBytes)
        assertEquals(downloaded, snapshot.usedBytes + snapshot.pendingBytes + snapshot.discardedBytes)
        assertEquals(discarded, snapshot.discardedByKind.values.sum())
    }
}
