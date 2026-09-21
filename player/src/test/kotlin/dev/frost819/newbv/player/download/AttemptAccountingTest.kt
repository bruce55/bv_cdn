package dev.frost819.newbv.player.download

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttemptAccountingTest {
    @Test
    fun `late cancelled loser reports only its own uncopied bytes once`() {
        val reports = mutableListOf<Pair<DownloadOverheadKind, Long>>()
        val accounting = AttemptAccounting { kind, bytes -> reports.add(kind to bytes) }
        accounting.preservePrefix(128)
        accounting.preservePrefix(256)
        accounting.discard()
        assertTrue(reports.isEmpty())
        accounting.finish(1024, DownloadOverheadKind.Rescue)
        accounting.discard()
        accounting.finish(1024, DownloadOverheadKind.Rescue)
        assertEquals(listOf(DownloadOverheadKind.Rescue to 768L), reports)
    }

    @Test
    fun `winning probe is useful despite later coordinator cleanup`() {
        val reports = mutableListOf<Long>()
        val accounting = AttemptAccounting { _, bytes -> reports.add(bytes) }
        accounting.finish(65536, DownloadOverheadKind.Probe)
        accounting.retain()
        accounting.discard()
        assertTrue(reports.isEmpty())
    }

    @Test
    fun `finished failure awaits coordinator disposition before counting waste`() {
        val reports = mutableListOf<Long>()
        val accounting = AttemptAccounting { _, bytes -> reports.add(bytes) }
        accounting.finish(1024, DownloadOverheadKind.Failure)
        accounting.preservePrefix(400)
        assertTrue(reports.isEmpty())
        accounting.discard()
        assertEquals(listOf(624L), reports)
    }

    @Test
    fun `readiness rescue waits for both observation floor and estimated transfer time`() {
        assertFalse(ReadinessRescuePolicy.observationComplete(0, null))
        assertFalse(ReadinessRescuePolicy.observationComplete(1_999_999_999, 100_000_000))
        assertTrue(ReadinessRescuePolicy.observationComplete(2_000_000_000, null))
        assertFalse(ReadinessRescuePolicy.observationComplete(3_000_000_000, 4_000_000_000))
        assertTrue(ReadinessRescuePolicy.observationComplete(4_000_000_000, 4_000_000_000))
    }
}
