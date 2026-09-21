package dev.frost819.newbv.player.download

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded coordinator dispatch with live priority at each available execution slot.
 * The supplied executor is owned by this scheduler and is shut down by [close].
 * [onCapacityAvailable] is a nonblocking refill signal, invoked outside the scheduler lock;
 * callers should coalesce it asynchronously instead of entering reader scheduling inline.
 */
internal class BoundedDownloadExecutor(
    private val maxConcurrent: Int,
    private val maxQueued: Int = maxConcurrent,
    executor: ExecutorService? = null,
    private val onCapacityAvailable: () -> Unit = {},
) : AutoCloseable {
    /** Immediate demand first, then earliest media position; ties retain preference and submission order. */
    data class Priority(
        val urgent: Boolean = false,
        val preferred: Boolean = false,
        val mediaTimeMs: Long? = null,
    )

    init {
        require(maxConcurrent > 0)
        require(maxQueued >= 0)
    }

    private val executor =
        executor ?: Executors.newFixedThreadPool(maxConcurrent) { task ->
            Thread(task, "vod-coordinator").apply { isDaemon = true }
        }
    private val lock = Any()
    private val waiting = mutableListOf<Work<*>>()
    private val active = linkedMapOf<Work<*>, Dispatch>()
    private var sequence = 0L
    private var draining = false
    private var closed = false

    /** Number of waiting tasks; cancelled tasks are removed immediately. */
    val queuedCount: Int get() = synchronized(lock) { waiting.size }

    /** Dispatched tasks, including cancelled tasks whose bodies have not exited yet. */
    val activeCount: Int get() = synchronized(lock) { active.size }

    /**
     * Submits without blocking, or returns null when the waiting queue is full or closed.
     * [priority] is sampled outside the scheduler lock when waiting work can be dispatched.
     * Cancellation of running work does not make a slot available before its body exits.
     * [onComplete] observes terminal results, including cancellation, outside the scheduler lock.
     */
    fun <T> trySubmit(
        priority: () -> Priority,
        callable: Callable<T>,
        onComplete: (Future<T>) -> Unit = {},
    ): Future<T>? {
        val work: Work<T>
        val dispatch: Dispatch?
        synchronized(lock) {
            if (closed) return null
            val immediate = active.size < maxConcurrent && waiting.isEmpty()
            if (!immediate && waiting.size >= maxQueued) return null
            work = Work(priority, sequence++, callable, onComplete)
            dispatch = if (immediate) Dispatch(work).also { active[work] = it } else null
            if (dispatch == null) waiting.add(work)
        }
        if (dispatch != null) execute(dispatch) else drain()
        return work
    }

    private inner class Work<T>(
        val priority: () -> Priority,
        val sequence: Long,
        callable: Callable<T>,
        val onComplete: (Future<T>) -> Unit,
    ) : FutureTask<T>(callable) {
        override fun done() {
            val removed = synchronized(lock) { waiting.remove(this) }
            onComplete(this)
            if (removed) {
                drain()
                notifyCapacityAvailable()
            }
        }
    }

    private inner class Dispatch(
        val work: Work<*>,
    ) : Runnable {
        private val claimed = AtomicBoolean(false)

        override fun run() {
            if (!claimed.compareAndSet(false, true)) return
            try {
                work.run()
            } finally {
                finished(work)
            }
        }

        // shutdownNow or rejection may prevent a dispatched wrapper from running at all.
        fun abandon() {
            if (!claimed.compareAndSet(false, true)) return
            work.cancel(false)
            finished(work)
        }
    }

    private fun execute(dispatch: Dispatch) {
        try {
            executor.execute(dispatch)
        } catch (_: RejectedExecutionException) {
            dispatch.abandon()
        }
    }

    private fun finished(work: Work<*>) {
        synchronized(lock) { active.remove(work) }
        drain()
        notifyCapacityAvailable()
    }

    private fun notifyCapacityAvailable() {
        val available =
            synchronized(lock) {
                !closed && (waiting.size < maxQueued || (waiting.isEmpty() && active.size < maxConcurrent))
            }
        if (available) onCapacityAvailable()
    }

    // One caller chooses waiting work at a time. Priority callbacks and task execution
    // stay outside the lock, so completion/cancellation can proceed during either.
    private fun drain() {
        synchronized(lock) {
            if (closed || draining) return
            draining = true
        }
        while (true) {
            val candidates =
                synchronized(lock) {
                    if (closed || active.size >= maxConcurrent || waiting.isEmpty()) {
                        draining = false
                        return
                    }
                    waiting.toList()
                }
            val selected =
                candidates
                    .map { work ->
                        val priority =
                            try {
                                work.priority()
                            } catch (_: RuntimeException) {
                                Priority()
                            }
                        work to priority
                    }.minWithOrNull(
                        compareByDescending<Pair<Work<*>, Priority>> { it.second.urgent }
                            .thenBy { it.second.mediaTimeMs ?: Long.MAX_VALUE }
                            .thenByDescending { it.second.preferred }
                            .thenBy { it.first.sequence },
                    )?.first ?: continue
            val dispatch =
                synchronized(lock) {
                    if (closed || active.size >= maxConcurrent || !waiting.remove(selected)) {
                        null
                    } else {
                        Dispatch(selected).also { active[selected] = it }
                    }
                }
            if (dispatch != null) {
                execute(dispatch)
                // Another completion may have observed draining=true and already signalled
                // before this queue slot was freed. Signal the actual dequeue as well.
                notifyCapacityAvailable()
            }
        }
    }

    /** Cancels waiting and active work; running bodies retain their slots until they actually exit. */
    override fun close() {
        val tasks =
            synchronized(lock) {
                if (closed) return
                closed = true
                (waiting.toList() + active.keys).also { waiting.clear() }
            }
        tasks.forEach { it.cancel(true) }
        executor.shutdownNow().forEach { if (it is Dispatch) it.abandon() }
    }
}
