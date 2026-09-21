package dev.frost819.newbv.player.download

import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Bounded dispatch, live priority and cancellation are independent of socket timing. */
class BoundedDownloadExecutorTest {
    @Test
    fun `queued blocks dispatch by media position rather than insertion order`() {
        val executor = ManualExecutor()
        val order = mutableListOf<Int>()
        BoundedDownloadExecutor(1, 2, executor).use { scheduler ->
            assertNotNull(scheduler.trySubmit({ BoundedDownloadExecutor.Priority() }, Callable { order.add(0) }))
            assertNotNull(
                scheduler.trySubmit(
                    { BoundedDownloadExecutor.Priority(mediaTimeMs = 60000) },
                    Callable { order.add(2) },
                ),
            )
            assertNotNull(
                scheduler.trySubmit(
                    { BoundedDownloadExecutor.Priority(mediaTimeMs = 5000) },
                    Callable { order.add(1) },
                ),
            )
            repeat(3) { executor.runNext() }
            assertEquals(listOf(0, 1, 2), order)
        }
    }

    @Test
    fun `capacity callback observes released queue and worker capacity outside scheduler lock`() {
        val executor = ManualExecutor()
        val reference = AtomicReference<BoundedDownloadExecutor>()
        val observations = mutableListOf<Pair<Int, Int>>()
        BoundedDownloadExecutor(1, 1, executor, onCapacityAvailable = {
            val scheduler = reference.get()
            val lock =
                BoundedDownloadExecutor::class.java
                    .getDeclaredField(
                        "lock",
                    ).apply { isAccessible = true }
                    .get(scheduler)
            assertFalse(Thread.holdsLock(lock), "Refill callback must run outside scheduler lock")
            observations.add(scheduler.activeCount to scheduler.queuedCount)
        }).use { scheduler ->
            reference.set(scheduler)
            val running = assertNotNull(scheduler.trySubmit({ BoundedDownloadExecutor.Priority() }, Callable { 1 }))
            val queued = assertNotNull(scheduler.trySubmit({ BoundedDownloadExecutor.Priority() }, Callable { 2 }))
            assertNull(scheduler.trySubmit({ BoundedDownloadExecutor.Priority() }, Callable { 3 }))
            assertTrue(observations.isEmpty())
            queued.cancel(false)
            assertEquals(listOf(1 to 0), observations)
            executor.runNext()
            assertEquals(1, running.get())
            assertEquals(listOf(1 to 0, 0 to 0), observations)
        }
    }

    @Test
    fun `waiting queue is bounded and queued cancellation immediately makes room`() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val cancelledRan = AtomicBoolean(false)
        BoundedDownloadExecutor(1, 2).use { scheduler ->
            try {
                val running =
                    assertNotNull(
                        scheduler.trySubmit(
                            { BoundedDownloadExecutor.Priority() },
                            Callable {
                                started.countDown()
                                release.await()
                                0
                            },
                        ),
                    )
                assertTrue(started.await(2, TimeUnit.SECONDS))
                val cancelled =
                    assertNotNull(
                        scheduler.trySubmit(
                            { BoundedDownloadExecutor.Priority() },
                            Callable {
                                cancelledRan.set(true)
                                1
                            },
                        ),
                    )
                val next = assertNotNull(scheduler.trySubmit({ BoundedDownloadExecutor.Priority() }, Callable { 2 }))
                assertNull(scheduler.trySubmit({ BoundedDownloadExecutor.Priority() }, Callable { 3 }))
                assertEquals(1, scheduler.activeCount)
                assertEquals(2, scheduler.queuedCount)
                assertTrue(cancelled.cancel(false))
                assertEquals(1, scheduler.queuedCount)
                val replacement =
                    assertNotNull(scheduler.trySubmit({ BoundedDownloadExecutor.Priority() }, Callable { 4 }))
                release.countDown()
                assertEquals(0, running.get(2, TimeUnit.SECONDS))
                assertEquals(2, next.get(2, TimeUnit.SECONDS))
                assertEquals(4, replacement.get(2, TimeUnit.SECONDS))
                assertFalse(cancelledRan.get())
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `waiting priorities are reevaluated with urgent before preferred then FIFO`() {
        val release = CountDownLatch(1)
        val urgent = AtomicBoolean(false)
        val preferred = AtomicBoolean(false)
        val order = Collections.synchronizedList(mutableListOf<Int>())
        BoundedDownloadExecutor(1, 4).use { scheduler ->
            try {
                val running =
                    assertNotNull(
                        scheduler.trySubmit(
                            { BoundedDownloadExecutor.Priority() },
                            Callable {
                                release.await()
                            },
                        ),
                    )

                fun enqueue(
                    value: Int,
                    priority: () -> BoundedDownloadExecutor.Priority,
                ) = assertNotNull(scheduler.trySubmit(priority, Callable { order.add(value) }))
                val first = enqueue(1) { BoundedDownloadExecutor.Priority() }
                val second = enqueue(2) { BoundedDownloadExecutor.Priority(preferred = preferred.get()) }
                val third = enqueue(3) { BoundedDownloadExecutor.Priority(urgent = urgent.get()) }
                val fourth = enqueue(4) { BoundedDownloadExecutor.Priority() }
                urgent.set(true)
                preferred.set(true)
                release.countDown()
                listOf(running, first, second, third, fourth).forEach { it.get(2, TimeUnit.SECONDS) }
                assertEquals(listOf(3, 2, 1, 4), order.toList())
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `cancelling running future retains its slot until the body actually exits`() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val nextStarted = CountDownLatch(1)
        BoundedDownloadExecutor(1, 1).use { scheduler ->
            try {
                val running =
                    assertNotNull(
                        scheduler.trySubmit(
                            { BoundedDownloadExecutor.Priority() },
                            Callable {
                                started.countDown()
                                awaitIgnoringInterrupts(release)
                            },
                        ),
                    )
                assertTrue(started.await(2, TimeUnit.SECONDS))
                val next =
                    assertNotNull(
                        scheduler.trySubmit(
                            { BoundedDownloadExecutor.Priority(urgent = true) },
                            Callable {
                                nextStarted.countDown()
                                1
                            },
                        ),
                    )
                assertTrue(running.cancel(true))
                assertEquals(1, scheduler.activeCount)
                assertEquals(1, scheduler.queuedCount)
                assertFalse(nextStarted.await(100, TimeUnit.MILLISECONDS))
                release.countDown()
                assertEquals(1, next.get(2, TimeUnit.SECONDS))
            } finally {
                release.countDown()
            }
        }
    }

    @Test
    fun `close cancels queued and dispatched work that the underlying executor has not started`() {
        val executor = ManualExecutor()
        val scheduler = BoundedDownloadExecutor(2, 1, executor)
        val ran = AtomicBoolean(false)
        val futures =
            List(3) {
                assertNotNull(scheduler.trySubmit({ BoundedDownloadExecutor.Priority() }, Callable { ran.set(true) }))
            }
        assertEquals(2, scheduler.activeCount)
        assertEquals(1, scheduler.queuedCount)
        scheduler.close()
        scheduler.close()
        assertTrue(futures.all { it.isCancelled })
        assertEquals(0, scheduler.queuedCount)
        assertEquals(0, scheduler.activeCount)
        assertTrue(executor.isShutdown)
        assertFalse(ran.get())
        assertNull(scheduler.trySubmit({ BoundedDownloadExecutor.Priority() }, Callable { 1 }))
    }

    @Test
    fun `zero queue capacity admits only immediately available workers`() {
        val executor = ManualExecutor()
        BoundedDownloadExecutor(1, 0, executor).use { scheduler ->
            assertNotNull(scheduler.trySubmit({ BoundedDownloadExecutor.Priority() }, Callable { 1 }))
            assertNull(scheduler.trySubmit({ BoundedDownloadExecutor.Priority(urgent = true) }, Callable { 2 }))
            assertEquals(0, scheduler.queuedCount)
        }
    }

    private fun awaitIgnoringInterrupts(latch: CountDownLatch) {
        while (true) {
            try {
                latch.await()
                return
            } catch (_: InterruptedException) {
                // Simulate a coordinator doing cleanup after its Future is already cancelled.
            }
        }
    }

    private class ManualExecutor : AbstractExecutorService() {
        private val pending = mutableListOf<Runnable>()
        private var stopped = false

        override fun execute(command: Runnable) {
            if (stopped) throw RejectedExecutionException()
            pending.add(command)
        }

        fun runNext() {
            pending.removeAt(0).run()
        }

        override fun shutdown() {
            stopped = true
        }

        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return pending.toMutableList().also { pending.clear() }
        }

        override fun isShutdown(): Boolean = stopped

        override fun isTerminated(): Boolean = stopped && pending.isEmpty()

        override fun awaitTermination(
            timeout: Long,
            unit: TimeUnit,
        ): Boolean = isTerminated
    }
}
