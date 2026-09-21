package dev.frost819.newbv.player.download

import org.junit.jupiter.api.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shared HTTP gate reserves recovery capacity without preempting running attempts. */
class HttpAdmissionControllerTest {
    @Test
    fun `ordinary attempts leave one slot while recoveries obey the global cap`() {
        for (limit in listOf(2, 3, 8, 64)) {
            val gate = HttpAdmissionController(limit)
            val running = List(limit - 1) { assertNotNull(gate.tryAcquire(gate.ticket())) }
            val ordinary = gate.ticket()
            assertEquals(1, gate.freeSlots)
            assertEquals(0, gate.freeOrdinarySlots)
            assertNull(gate.tryAcquire(ordinary))
            val rescue = assertNotNull(gate.tryAcquire(gate.ticket(HttpAdmissionController.Priority.Recovery)))
            assertEquals(0, gate.freeSlots)
            val secondRescue = gate.ticket(HttpAdmissionController.Priority.Recovery)
            assertNull(gate.tryAcquire(secondRescue))
            running.first().close()
            assertNull(gate.tryAcquire(ordinary))
            val secondLease = assertNotNull(gate.tryAcquire(secondRescue))
            assertEquals(0, gate.freeSlots)
            rescue.close()
            secondLease.close()
            running.forEach { it.close() }
            assertEquals(limit, gate.freeSlots)
            assertNotNull(gate.tryAcquire(ordinary)).close()
        }
    }

    @Test
    fun `rescue wins next free slot even when older ordinary and probe tickets poll first`() {
        val gate = HttpAdmissionController(8)
        val running = List(7) { assertNotNull(gate.tryAcquire(gate.ticket())) }
        val runningRescue = assertNotNull(gate.tryAcquire(gate.ticket(HttpAdmissionController.Priority.Recovery)))
        val ordinary = gate.ticket()
        val probe = gate.ticket(HttpAdmissionController.Priority.Speculative)
        val rescue = gate.ticket(HttpAdmissionController.Priority.Recovery)
        assertNull(gate.tryAcquire(rescue))
        assertEquals(0, gate.freeSlots)
        // Merely asking for rescue capacity did not preempt any active lease.
        running.first().close()
        assertNull(gate.tryAcquire(probe) { error("Probe must not reserve memory ahead of rescue") })
        assertNull(gate.tryAcquire(ordinary) { error("Ordinary must not reserve memory ahead of rescue") })
        assertNotNull(gate.tryAcquire(rescue)).close()
        assertNotNull(gate.tryAcquire(ordinary)).close()
        assertNotNull(gate.tryAcquire(probe)).close()
        runningRescue.close()
        running.forEach { it.close() }
        assertEquals(8, gate.freeSlots)
    }

    @Test
    fun `ineligible recovery neither owns a slot nor blocks lower priority work`() {
        val gate = HttpAdmissionController(8)
        var ready = false
        val recovery =
            gate.register(
                { HttpAdmissionController.Order(HttpAdmissionController.Priority.Recovery) },
                { ready },
            )
        val ordinary = gate.ticket()
        assertFalse(gate.canDispatch(recovery))
        assertNull(gate.tryAcquire(recovery) { error("Ineligible recovery cannot reserve memory") })
        val ordinaryLease = assertNotNull(gate.tryAcquire(ordinary))
        assertEquals(7, gate.freeSlots)
        ready = true
        assertTrue(gate.canDispatch(recovery))
        assertNotNull(gate.tryAcquire(recovery)).close()
        ordinaryLease.close()
        assertEquals(8, gate.freeSlots)
    }

    @Test
    fun `recovery order is deadline then media time then FIFO and priorities refresh`() {
        val gate = HttpAdmissionController(8)
        var deadline = 30L
        val late =
            gate.register(
                { HttpAdmissionController.Order(HttpAdmissionController.Priority.Recovery, deadline, 0) },
            )
        val farther = gate.ticket(HttpAdmissionController.Priority.Recovery, deadline = 20, mediaTime = 200)
        val first = gate.ticket(HttpAdmissionController.Priority.Recovery, deadline = 20, mediaTime = 100)
        val same = gate.ticket(HttpAdmissionController.Priority.Recovery, deadline = 20, mediaTime = 100)
        assertFalse(gate.canDispatch(late))
        assertFalse(gate.canDispatch(farther))
        assertFalse(gate.canDispatch(same))
        assertTrue(gate.canDispatch(first))
        deadline = 10
        assertFalse(gate.canDispatch(first))
        for (ticket in listOf(late, first, same, farther)) {
            assertNotNull(gate.tryAcquire(ticket)).close()
        }
    }

    @Test
    fun `demand precedes ordinary which precedes speculative but all share ordinary cap`() {
        val gate = HttpAdmissionController(3)
        val speculative = gate.ticket(HttpAdmissionController.Priority.Speculative, mediaTime = 0)
        val ordinary = gate.ticket(mediaTime = 100)
        val demand = gate.ticket(HttpAdmissionController.Priority.Demand, mediaTime = 200)
        assertFalse(gate.canDispatch(speculative))
        assertFalse(gate.canDispatch(ordinary))
        val demandLease = assertNotNull(gate.tryAcquire(demand))
        val ordinaryLease = assertNotNull(gate.tryAcquire(ordinary))
        assertEquals(1, gate.freeSlots)
        assertNull(gate.tryAcquire(speculative))
        demandLease.close()
        assertNotNull(gate.tryAcquire(speculative)).close()
        ordinaryLease.close()
    }

    @Test
    fun `failed memory reservation owns no worker and readiness can unblock other tickets`() {
        val gate = HttpAdmissionController(8)
        var memoryReady = true
        val recovery =
            gate.register({
                HttpAdmissionController.Order(HttpAdmissionController.Priority.Recovery)
            }, { memoryReady })
        var reserveCalls = 0
        assertNull(
            gate.tryAcquire(recovery) {
                reserveCalls++
                memoryReady = false
                false
            },
        )
        assertEquals(1, reserveCalls)
        assertEquals(8, gate.freeSlots)
        assertEquals(7, gate.freeOrdinarySlots)
        val ordinaryLease = assertNotNull(gate.tryAcquire(gate.ticket()))
        memoryReady = true
        assertNotNull(gate.tryAcquire(recovery)).close()
        ordinaryLease.close()
    }

    @Test
    fun `ticket cancellation and lease closure are idempotent and distinct`() {
        val gate = HttpAdmissionController(8)
        val canceled = gate.ticket(HttpAdmissionController.Priority.Recovery)
        gate.cancel(canceled)
        gate.cancel(canceled)
        assertNull(gate.tryAcquire(canceled) { error("Canceled ticket cannot acquire") })
        val active = gate.ticket()
        val lease = assertNotNull(gate.tryAcquire(active))
        gate.cancel(active)
        assertEquals(7, gate.freeSlots)
        assertNull(gate.tryAcquire(active))
        lease.close()
        lease.close()
        assertEquals(8, gate.freeSlots)
        assertEquals(7, gate.freeOrdinarySlots)
    }

    @Test
    fun `concurrent grant and close of the same ticket cannot duplicate slot ownership`() {
        val gate = HttpAdmissionController(8)
        val ticket = gate.ticket(HttpAdmissionController.Priority.Recovery)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val leases = executor.invokeAll(List(32) { Callable { gate.tryAcquire(ticket) } }).mapNotNull { it.get() }
            assertEquals(1, leases.size)
            assertEquals(7, gate.freeSlots)
            executor.invokeAll(List(32) { Callable { leases.single().close() } }).forEach { it.get() }
            assertEquals(8, gate.freeSlots)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `ordinary dispatch orders media position before FIFO and failed reservations retain rank`() {
        val gate = HttpAdmissionController(8)
        val farther = gate.ticket(mediaTime = 60000)
        val nearer = gate.ticket(mediaTime = 5000)
        val samePosition = gate.ticket(mediaTime = 5000)
        assertNull(gate.tryAcquire(farther) { error("Farther work must wait") })
        assertNull(gate.tryAcquire(nearer) { false })
        assertNull(gate.tryAcquire(samePosition) { error("Failed reservation retains oldest ticket") })
        assertNotNull(gate.tryAcquire(nearer)).close()
        assertNotNull(gate.tryAcquire(samePosition)).close()
        assertNotNull(gate.tryAcquire(farther)).close()
        val unknownFirst = gate.ticket()
        val unknownSecond = gate.ticket()
        assertFalse(gate.canDispatch(unknownSecond))
        assertNotNull(gate.tryAcquire(unknownFirst)).close()
        assertNotNull(gate.tryAcquire(unknownSecond)).close()
    }

    @Test
    fun `new playback demand jumps ahead of catch up work without replacing queued ticket`() {
        val gate = HttpAdmissionController(8)
        val catchUp = gate.ticket(mediaTime = 2000)
        var demanded = false
        val blocking =
            gate.register({
                HttpAdmissionController.Order(
                    if (demanded) {
                        HttpAdmissionController.Priority.Demand
                    } else {
                        HttpAdmissionController.Priority.Ordinary
                    },
                    mediaTime = 4000,
                )
            })
        assertFalse(gate.canDispatch(blocking))
        demanded = true
        assertFalse(gate.canDispatch(catchUp))
        assertNotNull(gate.tryAcquire(blocking)).close()
        assertNotNull(gate.tryAcquire(catchUp)).close()
        val laterDemand = gate.ticket(HttpAdmissionController.Priority.Demand, mediaTime = 6000)
        val earlierDemand = gate.ticket(HttpAdmissionController.Priority.Demand, mediaTime = 3000)
        assertFalse(gate.canDispatch(laterDemand))
        assertNotNull(gate.tryAcquire(earlierDemand)).close()
        assertNotNull(gate.tryAcquire(laterDemand)).close()
    }

    @Test
    fun `different concurrent tickets cannot overbook one available ordinary slot`() {
        val gate = HttpAdmissionController(2)
        val tickets = List(8) { gate.ticket() }
        val executor = Executors.newFixedThreadPool(8)
        try {
            val leases =
                executor
                    .invokeAll(tickets.map { ticket -> Callable { gate.tryAcquire(ticket) } })
                    .mapNotNull { it.get() }
            assertEquals(1, leases.size)
            assertEquals(1, gate.freeSlots)
            assertEquals(0, gate.freeOrdinarySlots)
            leases.single().close()
            tickets.forEach(gate::cancel)
            assertEquals(2, gate.freeSlots)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun HttpAdmissionController.ticket(
        priority: HttpAdmissionController.Priority = HttpAdmissionController.Priority.Ordinary,
        deadline: Long = Long.MAX_VALUE,
        mediaTime: Long = Long.MAX_VALUE,
    ) = register({ HttpAdmissionController.Order(priority, deadline, mediaTime) })
}
