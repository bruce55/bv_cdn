package dev.frost819.newbv.player.download

import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals

class ProvenanceHandoffTest {
    @Test
    fun `cancellation before publication disposes late result`() {
        val ledger = ExactTransferLedger()
        val handoff = ProvenanceHandoff()
        handoff.discard()
        handoff.publish(result(ledger))
        handoff.discard()
        assertEquals(100L, ledger.snapshot().discardedBytes)
        assertEquals(0, ledger.snapshot().liveSourceCount)
    }

    @Test
    fun `publication before cancellation disposes queued result`() {
        val ledger = ExactTransferLedger()
        val handoff = ProvenanceHandoff()
        handoff.publish(result(ledger))
        handoff.discard()
        handoff.discard()
        assertEquals(100L, ledger.snapshot().discardedBytes)
        assertEquals(0L, ledger.snapshot().pendingBytes)
    }

    @Test
    fun `taking transfers ownership out of coordinator cleanup`() {
        val ledger = ExactTransferLedger()
        val handoff = ProvenanceHandoff()
        val retained = result(ledger)
        handoff.publish(retained)
        handoff.take()
        handoff.discard()
        assertEquals(100L, ledger.snapshot().pendingBytes)
        retained.delivered(0, 100)
        retained.close()
        assertEquals(100L, ledger.snapshot().usedBytes)
        assertEquals(0L, ledger.snapshot().discardedBytes)
    }

    @Test
    fun `publication cancellation race never strands a source`() {
        val ledger = ExactTransferLedger()
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(500) {
                val handoff = ProvenanceHandoff()
                val handle = result(ledger)
                executor
                    .invokeAll(listOf(Callable { handoff.publish(handle) }, Callable { handoff.discard() }))
                    .forEach { it.get() }
                assertEquals(0L, ledger.snapshot().pendingBytes)
                assertEquals(0, ledger.snapshot().liveSourceCount)
            }
        } finally {
            executor.shutdownNow()
        }
        assertEquals(50000L, ledger.snapshot().discardedBytes)
    }

    private fun result(ledger: ExactTransferLedger): ExactTransferLedger.Handle {
        val source = ledger.newSource()
        source.received(100)
        val handle = source.view(0, 100)
        source.finish(DownloadOverheadKind.Rescue)
        return handle
    }
}
