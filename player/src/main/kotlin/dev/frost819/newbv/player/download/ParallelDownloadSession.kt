package dev.frost819.newbv.player.download

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import dev.frost819.newbv.player.BuildConfig
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded VOD range transport. Audio and video share the same request budget and route health.
 * Indexed segments use balanced pieces; session-wide byte budgets scale with concurrency and heap.
 * Seeks retain useful session-owned work and validated bytes within bounded memory budgets.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class ParallelDownloadSession(
    client: OkHttpClient,
    private val config: ParallelDownloadConfig,
    private val source: VodPlaybackSource,
    private val monitor: DownloadMonitor,
    private val memorySample: (() -> DownloadMemorySnapshot?)? = null,
) : Closeable {
    private val client =
        client
            .newBuilder()
            .connectTimeout(
                8,
                TimeUnit.SECONDS,
            ).readTimeout(8, TimeUnit.SECONDS)
            .build()
    private val traceIds = AtomicLong()
    private val lastRescueWaitTrace = AtomicLong()
    private val limit = config.maxRequests.coerceIn(2, 64)
    private val minimumBlockBytes = config.minimumBlockKiB.coerceIn(0, 4096) * 1024L
    private val transferLedger = ExactTransferLedger()
    private val memoryBudget = DownloadMemoryBudget(limit, minimumBlockBytes, Runtime.getRuntime().maxMemory())
    private val cacheLimitBytes get() = memoryBudget.sharedLimitBytes
    private val cache =
        RangeBlockCache(memoryBudget.maximumBytes, memoryBudget::retainCached, memoryBudget::releaseCached)
    private val seekWindows = ConcurrentHashMap<DownloadTrack, SeekHint>()
    private val retainedLock = Any()
    private val retainedRanges = mutableListOf<RetainedRange>()
    private val seekWindowBytes =
        (
            memoryBudget.payloadLimitBytes /
                listOfNotNull(source.video, source.audio).size.coerceAtLeast(1)
        ).coerceAtLeast(1)
    private val refillQueued = AtomicBoolean()
    private val admissionExecutor =
        Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "vod-admission").apply { isDaemon = true }
        }
    private val executor =
        BoundedDownloadExecutor(
            maxConcurrent = limit,
            maxQueued = limit,
            onCapacityAvailable = ::requestRefill,
        )
    private val headExecutor =
        BoundedDownloadExecutor(
            maxConcurrent = 2,
            maxQueued = 2,
            onCapacityAvailable = ::requestRefill,
        )

    // Coordinators never execute socket reads: otherwise waiting for a hedge can exhaust
    // the same executor needed to run that hedge.
    private val requestExecutor =
        Executors.newFixedThreadPool(limit) { task ->
            Thread(task, "vod-range-request").apply { isDaemon = true }
        }
    private val startupTracks = listOfNotNull(source.video?.kind, source.audio?.kind).toSet()
    private val startupPolicy = StartupProbePolicy(limit, startupTracks)

    private fun startupScheduling() = !monitor.hasStartedPlayback() && !startupPolicy.isFinished()

    private fun publishStartup() {
        monitor.startupProbeStatus(
            if (DownloadTrack.Video in startupTracks) startupPolicy.validCount(DownloadTrack.Video) else 0,
            if (DownloadTrack.Audio in startupTracks) startupPolicy.validCount(DownloadTrack.Audio) else 0,
            startupScheduling(),
        )
    }

    private val startupAudioSlots = (limit / 4).coerceAtLeast(1)
    private val startupAudioPermit = Semaphore(startupAudioSlots, true)
    private val startupExecutor =
        Executors.newFixedThreadPool(2) { task ->
            Thread(task, "vod-startup").apply { isDaemon = true }
        }
    private val startupProbeCancellations = concurrentSet<() -> Unit>()
    private val httpAdmission = HttpAdmissionController(limit)

    private fun traceRescueWait(
        reason: String,
        bytes: Long,
    ) {
        if (!monitor.traceEnabled) return
        val now = System.nanoTime()
        val previous = lastRescueWaitTrace.get()
        if (now - previous < TimeUnit.SECONDS.toNanos(1) || !lastRescueWaitTrace.compareAndSet(previous, now)) return
        val usage = memoryBudget.usage()
        monitor.trace(
            "rescue_wait",
            "reason" to reason,
            "requestedBytes" to bytes,
            "rescueUsed" to usage.second,
            "rescueLimit" to memoryBudget.rescueLimitBytes,
            "globalSlotsFree" to httpAdmission.freeSlots,
        )
    }

    /** Recovery never cancels for a slot: callers establish free transport capacity first. */
    private fun reclaimForRecovery(
        key: Any,
        bytes: Long,
        owner: RangeDataSource,
        start: Long,
    ) = synchronized(admissionLock) {
        if (httpAdmission.freeSlots == 0) return@synchronized
        var missing = memoryBudget.recoveryShortfall(bytes, key)
        if (missing <= 0) return@synchronized
        cache.reclaimEligible(missing)
        missing = memoryBudget.recoveryShortfall(bytes, key)
        if (missing <= 0) return@synchronized
        // Wait for the previously retired allocation before selecting another victim.
        if (readers.any { it.hasPendingRetirement() }) return@synchronized
        // Snapshot candidates first, then revalidate under the owning reader lock.
        val victims =
            readers
                .flatMap { it.recoveryVictims() }
                .sortedWith(
                    compareBy<Pair<RangeDataSource, Pending>> { it.second.future.isDone }
                        .thenByDescending {
                            monitor.schedulingTimeMs(
                                it.first.track.kind,
                                it.second.start,
                            ) ?: Long.MIN_VALUE
                        },
                )
        for ((reader, victim) in victims) {
            if (reader === owner && victim.start <= start) continue
            if (memoryBudget.recoveryShortfall(bytes, key) <= 0) break
            if (reader.retireForMemory(victim)) {
                monitor.trace(
                    "recovery_reclaim",
                    "track" to reader.track.kind,
                    "start" to victim.start,
                    "bytes" to victim.length,
                    "state" to "cancellation_pending",
                )
                // Do not cancel additional work while this allocation is still being released.
                if (reader.hasReservation(victim.start)) return@synchronized
            }
        }
        val cached =
            cache
                .evictionCandidates()
                .filter { !it.pinned }
                .sortedByDescending { monitor.schedulingTimeMs(it.track.kind, it.start) ?: Long.MIN_VALUE }
        for (entry in cached) {
            if (memoryBudget.recoveryShortfall(bytes, key) <= 0) break
            if (entry.track == owner.track && entry.start <= start) continue
            if (readers.any { it.track == entry.track && it.protects(entry.start, entry.endExclusive) }) continue
            val released = cache.evictUnread(entry.track, entry.start, entry.endExclusive)
            if (released > 0) {
                monitor.trace(
                    "recovery_cache_evict",
                    "track" to entry.track.kind,
                    "start" to entry.start,
                    "end" to entry.endExclusive,
                    "cacheReferencesBytes" to released,
                )
            }
        }
    }

    private val resolver =
        CdnResolver(
            config,
            trace = { type, fields -> monitor.trace(type, *fields.toList().toTypedArray()) },
            traceEnabled = { monitor.traceEnabled },
        )
    private val readers = concurrentSet<RangeDataSource>()
    private val urgentRanges = concurrentSet<Any>()

    // One blocking range owns all new rescue copies across both audio and video.
    private val rescueFocus = AtomicReference<Any?>(null)
    private val splitActive = AtomicBoolean(false)
    private val recoveryExplorations = AtomicInteger()
    private val recoveryExplorationLimit = maxOf(1, limit / 4)

    private fun reserveRecoveryExplorationSlot(): Boolean {
        while (true) {
            val count = recoveryExplorations.get()
            if (count >= recoveryExplorationLimit) return false
            if (recoveryExplorations.compareAndSet(count, count + 1)) return true
        }
    }

    private val startupProbed = concurrentSet<String>()
    private val splitExecutor =
        ThreadPoolExecutor(
            2,
            2,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(2),
            { task -> Thread(task, "vod-range-split").apply { isDaemon = true } },
        )

    private inner class SplitLease(
        private val ownsSplitSlot: Boolean = true,
        private val onReleased: () -> Unit = {},
    ) {
        private var references = 1
        val cancelled = AtomicBoolean(false)
        private val cancellations = concurrentSet<() -> Unit>()

        @Synchronized
        fun retain(cancel: () -> Unit): Boolean {
            // A cancelled coordinator can reach launch after the owning wrapper exits.
            // It must not resurrect this lease or later release a newer split's slot.
            if (references == 0 || cancelled.get()) return false
            references++
            cancellations.add(cancel)
            return true
        }

        @Synchronized
        fun release(cancel: (() -> Unit)? = null) {
            if (cancel != null) cancellations.remove(cancel)
            if (references > 0 && --references == 0) {
                if (ownsSplitSlot) splitActive.set(false)
                onReleased()
            }
        }

        @Synchronized
        fun cancel() {
            cancelled.set(true)
            cancellations.forEach { it() }
        }
    }

    private data class PlannedWork(
        val track: DownloadTrack,
        val start: Long,
        val end: Long,
    )

    private val plannedWork = ConcurrentHashMap<Any, PlannedWork>()
    private val admissionLock = Any()
    private var lastAdmissionWaitTrace = 0L

    // Capacity notifications must never synchronously enter another reader while a completion
    // holds its lock. Coalescing keeps at most one refill queued behind the current pass.
    private fun requestRefill() {
        if (closed || !refillQueued.compareAndSet(false, true)) return
        try {
            admissionExecutor.execute {
                refillQueued.set(false)
                if (!closed) fillReadAhead()
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            refillQueued.set(false)
        }
    }

    // One selector owns track ordering. Retained bytes and in-flight reservations, rather
    // than completed block counts or a second time window, govern how far it may prepare.
    private var lastMemoryRefreshNanos = 0L

    private fun refreshMemoryBudget() {
        val sample = memorySample ?: return
        val now = System.nanoTime()
        if (lastMemoryRefreshNanos != 0L && now - lastMemoryRefreshNanos < TimeUnit.SECONDS.toNanos(5)) return
        lastMemoryRefreshNanos = now
        memoryBudget.refreshMemory(sample())
        val excess = memoryBudget.snapshot().sharedUsedBytes - memoryBudget.sharedLimitBytes
        if (excess > 0) cache.reclaimEligible(excess)
        monitor.trace("memory_admission_budget", "capacity" to memoryBudget.capacityFields())
    }

    private fun fillReadAhead() =
        synchronized(admissionLock) {
            refreshMemoryBudget()
            val excluded = mutableSetOf<RangeDataSource>()
            repeat(limit * 2) {
                val snapshots = readers.mapNotNull { reader -> reader.admissionCandidate()?.let { reader to it } }
                val pending = readers.sumOf { it.pendingBlockCount() }
                val eligible = snapshots.filter { it.first !in excluded }
                val chosen =
                    ReadAheadAdmission.choose(
                        snapshots.map { it.second },
                        snapshots.filter { it.first in excluded }.mapTo(mutableSetOf()) { it.second.key },
                    ) ?: run {
                        if (excluded.isEmpty()) monitor.schedulerWait("no_reader_work")
                        val now = System.nanoTime()
                        if (monitor.traceEnabled && now - lastAdmissionWaitTrace >= TimeUnit.SECONDS.toNanos(1)) {
                            lastAdmissionWaitTrace = now
                            monitor.trace(
                                "read_ahead_wait",
                                "pendingBlocks" to pending,
                                "sharedUsedBytes" to memoryBudget.snapshot().sharedUsedBytes,
                                "sharedLimitBytes" to memoryBudget.sharedLimitBytes,
                                "reason" to
                                    when {
                                        excluded.isNotEmpty() -> "lagging_track_capacity"
                                        eligible.isEmpty() -> "no_reader_work"
                                        else -> "no_eligible_work"
                                    },
                                "blockedTracks" to
                                    snapshots
                                        .filter {
                                            it.first in excluded
                                        }.map { it.first.track.kind },
                                "preparedTimesMs" to
                                    snapshots.associate { it.first.track.kind to it.second.preparedTimeMs },
                            )
                        }
                        return@synchronized
                    }
                val reader = eligible.first { it.second.key == chosen.key }.first
                val headCharges =
                    readers
                        .mapNotNull { it.headCharge() }
                        .groupBy({ it.first }, { it.second })
                        .mapValues { (_, charges) -> charges.maxOrNull() ?: 0L }
                if (!reader.admitOne(headCharges)) {
                    excluded.add(reader)
                    return@repeat
                }
                monitor.trace(
                    "read_ahead_admission",
                    "track" to reader.track.kind.name,
                    "readTimeMs" to chosen.readTimeMs,
                    "preparedTimeMs" to chosen.preparedTimeMs,
                    "nextTimeMs" to chosen.nextTimeMs,
                    "pendingBlocks" to pending,
                    "urgent" to chosen.urgent,
                )
            }
        }

    @Volatile private var closed = false

    init {
        memoryBudget.configureTracks(startupTracks)
        if (memorySample != null) {
            // Also revisit pressure when Media3 is not reading; no dependence on the log toggle.
            admissionExecutor.scheduleWithFixedDelay(::requestRefill, 5, 5, TimeUnit.SECONDS)
        }
        val tracks = listOfNotNull(source.video, source.audio).toSet()
        monitor.setAvailabilitySampler(tracks.map { it.kind }.toSet()) {
            if (closed) {
                emptyMap()
            } else {
                val ranges = mutableMapOf<DownloadTrack, MutableList<LongRange>>()
                for (entry in cache.ranges()) {
                    if (entry.track in tracks) {
                        ranges.getOrPut(entry.track.kind) { mutableListOf() }.add(entry.start until entry.endExclusive)
                    }
                }
                // Validated queued bodies can still be resident even when the LRU could not admit them.
                for (reader in readers) {
                    if (reader.track in tracks) {
                        ranges.getOrPut(reader.track.kind) { mutableListOf() }.addAll(reader.residentRanges())
                    }
                }
                ranges
            }
        }
        monitor.setExactTransferSampler(transferLedger::snapshot)
        monitor.setInFlightSampler {
            if (closed) {
                emptyMap()
            } else {
                readers
                    .filter { it.track in tracks }
                    .groupBy { it.track.kind }
                    .mapValues { (_, active) -> active.flatMap { it.inFlightRanges() } }
            }
        }
        monitor.setTraceSampler {
            if (!closed && monitor.traceEnabled) {
                val storage = KnownDownloadStorage()
                cache.sampleStorage().forEach { (track, bytes) -> storage.add(bytes, track, "cache") }
                // Each reader samples independently; never nest the cache and reader locks.
                // Completed-result arrays also cover queued Futures without invoking their get().
                val sampledReaders = readers.toList()
                sampledReaders.forEach { it.sampleStorage(storage) }
                val storageFields = storage.fields() + ("detachedReaders" to sampledReaders.count { it.isDetached() })
                val usage = memoryBudget.usage()
                val shared = memoryBudget.snapshot()
                val retention = cache.retentionStats()
                monitor.trace(
                    "session_capacity",
                    "memoryBudget" to memoryBudget.capacityFields(),
                    "transferMeasurements" to
                        monitor.snapshots.value.transferMeasurements
                            .fields(),
                    "sharedUsedBytes" to shared.sharedUsedBytes,
                    "sharedLimitBytes" to shared.sharedLimitBytes,
                    "cacheOnlyBytes" to shared.cachedOnlyBytes,
                    "protectedCacheBytes" to retention.protectedBytes,
                    "evictableCacheBytes" to retention.evictableBytes,
                    "pinnedCacheBytes" to retention.pinnedBytes,
                    "storage" to storageFields,
                    "readers" to readers.size,
                    "globalSlotsFree" to httpAdmission.freeSlots,
                    "ordinarySlotsFree" to httpAdmission.freeOrdinarySlots,
                    "maxRequests" to limit,
                    "payloadUsed" to usage.first,
                    "payloadLimit" to memoryBudget.payloadLimitBytes,
                    "reservationCopyFactor" to 2,
                    "cacheBytes" to cache.usedBytes,
                    "cacheLimit" to cacheLimitBytes,
                    "cacheEntries" to cache.entryCount,
                    "rescueUsed" to usage.second,
                    "rescueLimit" to memoryBudget.rescueLimitBytes,
                    "plannedWork" to plannedWork.size,
                    "coordinatorActive" to executor.activeCount,
                    "coordinatorQueued" to executor.queuedCount,
                    "coordinatorQueueLimit" to limit,
                    "headCoordinatorActive" to headExecutor.activeCount,
                    "headCoordinatorQueued" to headExecutor.queuedCount,
                    "recoveryExplorations" to recoveryExplorations.get(),
                    "recoveryExplorationLimit" to recoveryExplorationLimit,
                    "startup" to startupScheduling(),
                )
                readers.forEach { it.traceHeartbeat() }
            }
        }
    }

    /** Creates readers for one representation while preserving a sequential compatibility path. */
    fun factory(
        track: MediaTrackSource,
        fallback: DataSource.Factory,
    ): DataSource.Factory =
        DataSource.Factory {
            ReopeningDataSource(track, fallback)
        }

    /** Cancels all calls and queued work for the old video or quality. */
    override fun close() {
        closed = true
        monitor.setTraceSampler {}
        readers.toList().forEach { it.close() }
        admissionExecutor.shutdownNow()
        startupExecutor.shutdownNow()
        executor.close()
        headExecutor.close()
        // An admitted attempt can be queued while its predecessor finishes result handoff.
        // Dropping that Runnable would skip its slot, prefix and byte-lease cleanup. Drain it:
        // closed/cancelled is already visible and ensureAttempt prevents another HTTP start.
        requestExecutor.shutdownNow().filterIsInstance<HttpAttemptTask>().forEach { it.run() }
        splitExecutor.shutdownNow()
        cache.close()
        seekWindows.clear()
    }

    // Once-only execution also covers admitted work returned by shutdownNow before it started.
    private class HttpAttemptTask(
        private val execute: () -> Unit,
    ) : Runnable {
        private val started = AtomicBoolean()

        override fun run() {
            if (started.compareAndSet(false, true)) execute()
        }
    }

    private class UnsupportedRange(
        val url: String,
    ) : IOException("Server does not support ranges")

    private data class Block(
        val id: Long,
        val start: Long,
        val bytes: ByteArray,
        val total: Long,
        val offset: Int = 0,
        val length: Int = bytes.size,
        val release: () -> Unit = {},
        val provenance: ExactTransferLedger.Handle? = null,
    )

    private data class RescuePrefix(
        val bytes: ByteArray = ByteArray(0),
        val provenance: ExactTransferLedger.Handle? = null,
    )

    // CompletableFuture requires API 24; this transport also supports Android 5/6.
    private class ResultFuture<T> : FutureTask<T>(Callable { error("Result is supplied by the producer") }) {
        fun complete(value: T) = set(value)

        fun completeExceptionally(error: Throwable) = setException(error)
    }

    private data class Pending(
        val id: Long,
        val future: Future<Block>,
        val length: Long,
        val start: Long,
        val release: () -> Unit,
        val retained: RetainedRange? = null,
    )

    private data class SeekHint(
        val range: LongRange,
        val expires: Long,
    )

    private data class StagedRange(
        val range: LongRange,
        val id: Long,
        val key: Any? = null,
        val reused: Pending? = null,
    )

    /** Marks the imminent Media3 reader replacement so useful requests survive its close/open cycle. */
    fun prepareSeek(positionMs: Long) {
        if (closed) return
        resolver.beginSeek()
        monitor.beginSeek(positionMs)
        val expires = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        seekWindows.clear()
        cache.abandonUnread()
        listOfNotNull(source.video, source.audio).forEach { track ->
            val range = monitor.seekWindow(track.kind, positionMs, seekWindowBytes) ?: return@forEach
            seekWindows[track.kind] = SeekHint(range, expires)
            pruneRetained(track, range)
            monitor.trace(
                "seek_window",
                "track" to track.kind.name,
                "positionMs" to positionMs,
                "start" to range.first,
                "end" to range.last,
            )
        }
    }

    // Reader lifetimes belong to Media3; retained transport jobs belong to the playback session.
    private inner class ReopeningDataSource(
        val track: MediaTrackSource,
        val fallback: DataSource.Factory,
    ) : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var reader: RangeDataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            listeners.add(transferListener)
            reader?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            check(reader == null) { "Data source is already open" }
            return RangeDataSource(track, fallback)
                .also { next ->
                    reader = next
                    readers.add(next)
                    listeners.forEach(next::addTransferListener)
                }.open(dataSpec)
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int = requireNotNull(reader).read(buffer, offset, length)

        override fun getUri(): Uri? = reader?.getUri()

        override fun getResponseHeaders(): Map<String, List<String>> = reader?.getResponseHeaders() ?: emptyMap()

        override fun close() {
            val previous = reader
            reader = null
            previous?.close()
        }
    }

    private inner class RetainedRange(
        val owner: RangeDataSource,
        val pending: Pending,
    ) {
        var claimed = false // guarded by retainedLock
        private val released = AtomicBoolean(false)

        @Volatile var onReady: ((Block) -> Unit)? = null
        val track get() = owner.track
        val range get() = pending.start..(pending.start + pending.length - 1)

        fun release(cancel: Boolean = false) {
            if (!released.compareAndSet(false, true)) return
            synchronized(retainedLock) {
                retainedRanges.remove(this)
                onReady = null
            }
            if (cancel) pending.future.cancel(true)
            pending.release()
            owner.retainedOwned.remove(this)
            owner.finishDetached()
        }

        fun offerAgain(): Boolean =
            synchronized(retainedLock) {
                if (released.get()) return@synchronized false
                claimed = false
                onReady = null
                if (this !in retainedRanges) retainedRanges.add(this)
                true
            }
    }

    private fun pruneRetained(
        track: MediaTrackSource,
        window: LongRange,
    ) {
        val removed =
            synchronized(retainedLock) {
                retainedRanges
                    .filter {
                        it.track == track &&
                            !overlaps(
                                it.range,
                                window,
                            )
                    }.also { retainedRanges.removeAll(it.toSet()) }
            }
        removed.forEach { it.release(cancel = true) }
        if (removed.isNotEmpty()) {
            monitor.trace(
                "seek_retained_cancel",
                "track" to track.kind.name,
                "count" to removed.size,
            )
        }
    }

    private fun overlaps(
        first: LongRange,
        second: LongRange,
    ): Boolean = first.first <= second.last && second.first <= first.last

    private inner class RangeDataSource(
        val track: MediaTrackSource,
        private val fallback: DataSource.Factory,
    ) : BaseDataSource(true) {
        private val readerId = traceIds.incrementAndGet()
        private val lastSchedulerTrace = AtomicLong()

        @Volatile private var lastSchedulerReason = "reader_created"

        @Volatile private var lastReadNanos = 0L
        private val calls = concurrentSet<Call>()
        private val liveTraceAttempts = concurrentSet<Attempt>()
        private val pending = java.util.ArrayDeque<Pending>()
        private val completedBlocks = ConcurrentHashMap<Long, Block>()
        val retainedOwned = concurrentSet<RetainedRange>()

        @Volatile private var detached = false

        @Volatile private var awaitedPending: Pending? = null
        private val memoryDemandKey = Any()

        @Volatile private var memoryDeniedSince = 0L
        private val revokedBlocks = concurrentSet<Long>()
        private val retiringRanges = concurrentSet<Long>()
        private val recoveryPayloadKeys = ConcurrentHashMap<Long, Any>()
        private val payloadReservations = ConcurrentHashMap<Long, Long>()
        private val payloadStorage = mutableMapOf<Long, ByteArray>()
        private val payloadProvenance = mutableMapOf<Long, ExactTransferLedger.Handle>()
        private val payloadUsers = mutableMapOf<Long, Int>()
        private val releasedPayloads = mutableSetOf<Long>()
        private val protectedPayloads = mutableSetOf<Long>()
        private val consumedProtectedPayloads = mutableSetOf<Long>()

        @Volatile private var awaitedWork: Future<Block>? = null
        private val ids = concurrentSet<Long>()
        private val plannedRanges = java.util.ArrayDeque<LongRange>()
        private val workKeys = ConcurrentHashMap<Long, Any>()
        private var startupPartition = true
        private var speedEvidenceEpoch = 0L
        private var sequentialWorkerId: Long? = null

        @Volatile private var awaitedRange: LongRange? = null

        @Volatile private var cancelled = false

        @Volatile private var generation = 0L

        @Volatile private var readinessEpoch = 0L

        @Volatile private var sequential: DataSource? = null

        private var sequentialLease: HttpAdmissionController.Lease? = null

        @Volatile private var sequentialPermit = false
        private val sequentialAudioPermit = AtomicBoolean(false)
        private var spec: DataSpec? = null
        private var uri: Uri? = null

        @Volatile private var block: Block? = null

        @Volatile private var blockOffset = 0

        @Volatile private var position = 0L
        private var endExclusive = 0L
        private var nextStart = 0L

        @Volatile private var admissionReady = false
        private var total: Long? = null
        private var started = false
        private var sequentialHost: String? = null
        private var sequentialBegan = 0L
        private var sequentialBytes = 0L
        private var sequentialCompleted = false
        private var sequentialFailure: String? = null

        fun residentRanges(): List<LongRange> = completedBlocks.values.map { it.start until (it.start + it.length) }

        fun isDetached(): Boolean = detached

        fun sampleStorage(storage: KnownDownloadStorage) {
            completedBlocks.values.forEach { storage.add(it.bytes, track.kind, "reader") }
            block?.let {
                storage.add(it.bytes, track.kind, "reader")
                if (blockOffset >= it.length) storage.add(it.bytes, track.kind, "consumedCurrent")
            }
            liveTraceAttempts.forEach { attempt ->
                storage.add(attempt.prefix, track.kind, "attempt")
                attempt.partialBody?.let { storage.add(it, track.kind, "attempt") }
            }
        }

        fun inFlightRanges(): List<LongRange> =
            liveTraceAttempts.mapNotNull { attempt ->
                val range = attempt.range ?: return@mapNotNull null
                if (attempt.partialBody == null || attempt.cancelled.get()) return@mapNotNull null
                val count = attempt.received.coerceIn(0L, range.last - range.first + 1)
                if (count == 0L) null else range.first until (range.first + count)
            }

        // Called from existing timed coordinator waits so stalled sockets remain observable.
        // Sampling is independent of UI diagnostics and can be enabled during playback.
        fun traceHeartbeat() {
            traceScheduler(lastSchedulerReason)
            liveTraceAttempts.forEach { traceAttemptProgress(it) }
        }

        private fun traceScheduler(reason: String) {
            lastSchedulerReason = reason
            if (!monitor.traceEnabled) return
            val now = System.nanoTime()
            val previous = lastSchedulerTrace.get()
            if (now - previous < TimeUnit.SECONDS.toNanos(1) || !lastSchedulerTrace.compareAndSet(previous, now)) return
            synchronized(pending) {
                val usage = memoryBudget.usage()
                val shared = memoryBudget.snapshot()
                val unfinished = pending.count { !it.future.isDone }
                val ready = pending.size - unfinished
                monitor.trace(
                    "scheduler",
                    "reader" to readerId,
                    "track" to track.kind.name,
                    "generation" to generation,
                    "reason" to reason,
                    "sincePlayerReadMs" to
                        lastReadNanos.takeIf { it != 0L }?.let { TimeUnit.NANOSECONDS.toMillis(now - it) },
                    "positionBytes" to position,
                    "nextStart" to nextStart,
                    "endExclusive" to endExclusive,
                    "pendingUnfinished" to unfinished,
                    "pendingCompleted" to ready,
                    "pendingBytes" to pending.sumOf { it.length },
                    "plannedRanges" to plannedRanges.size,
                    "awaitedStart" to awaitedRange?.first,
                    "awaitedEnd" to awaitedRange?.last,
                    "awaitedUnfinished" to (awaitedWork?.isDone == false),
                    "readerReservedBytes" to payloadReservations.values.sum(),
                    "lastAdmissionRangeBytes" to lastAdmissionRangeBytes,
                    "lastAdmissionHeadroomBytes" to lastAdmissionHeadroomBytes,
                    "memoryBudget" to memoryBudget.capacityFields(),
                    "sharedUsedBytes" to shared.sharedUsedBytes,
                    "sharedLimitBytes" to shared.sharedLimitBytes,
                    "cacheOnlyBytes" to shared.cachedOnlyBytes,
                    "retainedExplorations" to protectedPayloads.size,
                    "trackHttpCalls" to calls.size,
                    "sequential" to sequentialPermit,
                    "sequentialReceivedBytes" to sequentialBytes,
                    "ordinarySlotsFree" to httpAdmission.freeOrdinarySlots,
                    "globalSlotsFree" to httpAdmission.freeSlots,
                    "maxRequests" to limit,
                    "payloadUsed" to usage.first,
                    "payloadLimit" to memoryBudget.payloadLimitBytes,
                    "reservationCopyFactor" to 2,
                    "rescueUsed" to usage.second,
                    "rescueLimit" to memoryBudget.rescueLimitBytes,
                    "startup" to startupScheduling(),
                    "paused" to monitor.isPlaybackPaused(),
                    "rebuffering" to monitor.isRebuffering(),
                    "explorationActive" to splitActive.get(),
                )
            }
        }

        override fun open(dataSpec: DataSpec): Long {
            admissionReady = false
            generation++
            cancelled = false
            checkOpen()
            readers.add(this)
            spec = dataSpec
            uri = dataSpec.uri
            position = dataSpec.position
            cache.setReadPosition(track, position)
            speedEvidenceEpoch = resolver.currentEvidenceEpoch()
            val openedEpoch = monitor.beginRead(track.kind, position)
            readinessEpoch = openedEpoch
            monitor.trace(
                "reader_open",
                "reader" to readerId,
                "track" to track.kind.name,
                "start" to position,
                "length" to dataSpec.length,
                "generation" to generation,
            )
            startupPartition = true
            transferInitializing(dataSpec)
            try {
                val length =
                    if (dataSpec.httpMethod != DataSpec.HTTP_METHOD_GET || !config.enabled) {
                        startSequential(dataSpec.uri.toString())
                    } else {
                        val requestEnd =
                            if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                                Long.MAX_VALUE
                            } else {
                                if (dataSpec.length > Long.MAX_VALUE - position) throw IOException("Range overflow")
                                position + dataSpec.length
                            }
                        endExclusive = requestEnd
                        val windowEnd = minOf(requestEnd, position + minOf(seekWindowBytes, Long.MAX_VALUE - position))
                        if (windowEnd > position) pruneRetained(track, position..(windowEnd - 1))
                        try {
                            val firstWork =
                                reuseAt(position, requestEnd) ?: requestRequired(
                                    position,
                                    minOf(
                                        requestEnd - 1,
                                        position + RangePartition.TARGET_BYTES - 1,
                                        nextReusableStart(position)?.minus(1) ?: Long.MAX_VALUE,
                                    ),
                                )
                            val first = awaitBlock(firstWork)
                            readyForRead(first)
                            total = first.total
                            endExclusive = minOf(requestEnd, first.total)
                            block = first
                            nextStart = position + first.length
                            admissionReady = true
                            fillWindow()
                            if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else endExclusive - position
                        } catch (unsupported: UnsupportedRange) {
                            startSequential(unsupported.url)
                        }
                    }
                started = true
                transferStarted(dataSpec)
                return length
            } catch (error: Exception) {
                close()
                throw asIo(error)
            }
        }

        private fun requestRequired(
            start: Long,
            end: Long,
        ): Pending {
            while (!reservePayload(start, end - start + 1)) waitForPlanning()
            val id = plan(start, end)
            val token = generation
            awaitedRange = start..end
            var future: Future<Block>? = null
            while (future == null) {
                checkOpen()
                future =
                    headExecutor.trySubmit(
                        priority = { BoundedDownloadExecutor.Priority(urgent = true) },
                        callable =
                            Callable {
                                try {
                                    rememberBlock(fetchStartupHead(start, end, id, token), token)
                                } catch (error: Exception) {
                                    forgetFailedRange(start)
                                    throw error
                                }
                            },
                        onComplete = { rangeFinished(start, it) },
                    )
                if (future == null) waitForPlanning()
            }
            return Pending(id, future, end - start + 1, start, { releasePayload(start) })
        }

        private fun awaitBlock(
            initial: Pending,
            refill: Boolean = false,
        ): Block {
            var current = initial
            var tracedReuse = false
            try {
                while (true) {
                    awaitedPending = current
                    awaitedWork = current.future
                    awaitedRange = current.start..(current.start + current.length - 1)
                    if (current.retained != null && !tracedReuse) {
                        tracedReuse = true
                        monitor.trace("seek_reuse_wait", "reader" to readerId, "start" to current.start)
                    }
                    checkReadOpen()
                    try {
                        return current.future.get(100, TimeUnit.MILLISECONDS).also {
                            // close() already released this view or handed its ownership back.
                            // Do not let an old loader resume after a concurrent seek completed it.
                            checkReadOpen()
                            readyForRead(it)
                            awaitedPending = null
                        }
                    } catch (_: TimeoutException) {
                        if (refill) fillWindow()
                    } catch (error: Exception) {
                        checkReadOpen()
                        if (current.retained == null || error is InterruptedException) {
                            throw asIo(if (error is ExecutionException) error.cause ?: error else error)
                        }
                        // An old request can fail just as the new reader adopts it. Retry the
                        // missing view normally instead of inheriting a dead Future across a seek.
                        current.release()
                        monitor.trace(
                            "seek_retained_retry",
                            "reader" to readerId,
                            "track" to track.kind.name,
                            "start" to current.start,
                            "end" to current.start + current.length - 1,
                        )
                        current = requestRequired(current.start, current.start + current.length - 1)
                    }
                }
            } finally {
                awaitedRange = null
                awaitedWork = null
            }
        }

        private fun rememberBlock(
            result: Block,
            token: Long,
        ): Block {
            var retainedByPayload = false
            try {
                synchronized(pending) {
                    if (result.id in revokedBlocks) throw InterruptedIOException("Range retired for memory recovery")
                    if (payloadReservations.containsKey(result.start) && !payloadStorage.containsKey(result.start)) {
                        memoryBudget.attachPayload(result.bytes)
                        payloadStorage[result.start] = result.bytes
                        result.provenance?.let { payloadProvenance[result.start] = it }
                    }
                    retainedByPayload =
                        result.provenance != null &&
                        payloadProvenance[result.start] === result.provenance
                    if (!cancelled && generation == token) completedBlocks[result.start] = result
                    val stored =
                        cache.put(
                            track,
                            result.start,
                            result.bytes,
                            result.total,
                            abandoned = cancelled || detached || generation != token,
                            provenance = result.provenance,
                        )
                    if (stored) releaseCachedRetained(result.start)
                    return result.copy(release = { releasePayload(result.start) })
                }
            } finally {
                if (!retainedByPayload) result.provenance?.close()
            }
        }

        private fun rangeFinished(
            start: Long,
            future: Future<Block>,
        ) {
            if (!future.isDone) return
            val result =
                try {
                    future.get()
                } catch (_: Exception) {
                    forgetFailedRange(start)
                    return
                }
            synchronized(pending) {
                if (result.id in revokedBlocks || !payloadReservations.containsKey(start)) return
                retainedOwned.filter { it.pending.start == start }.forEach { it.onReady?.invoke(result) }
                if (cache.put(
                        track,
                        result.start,
                        result.bytes,
                        result.total,
                        abandoned = cancelled || detached,
                        provenance = result.provenance,
                    )
                ) {
                    releaseCachedRetained(start)
                }
            }
            finishDetached()
        }

        private fun forgetFailedRange(start: Long) {
            val failed = retainedOwned.filter { it.pending.start == start }
            failed.forEach { it.release() }
            if (failed.isNotEmpty()) {
                monitor.trace(
                    "seek_retained_failed",
                    "reader" to readerId,
                    "track" to track.kind.name,
                    "start" to start,
                )
            }
            finishDetached()
        }

        private fun releaseCachedRetained(start: Long) {
            val completed =
                synchronized(retainedLock) {
                    retainedOwned.filter { !it.claimed && it.pending.start == start }.onEach {
                        it.claimed = true // ownership is moving to the cache, never to another reader
                        retainedRanges.remove(it)
                    }
                }
            completed.forEach { it.release() }
        }

        fun finishDetached() {
            if (!detached ||
                retainedOwned.isNotEmpty() ||
                liveTraceAttempts.isNotEmpty() ||
                workKeys.isNotEmpty()
            ) {
                return
            }
            cancelled = true
            readers.remove(this)
            completedBlocks.clear()
            spec = null
        }

        private fun nextReusableStart(start: Long): Long? {
            val flight =
                synchronized(retainedLock) {
                    retainedRanges
                        .filter {
                            it.track == track && it.pending.start > start
                        }.minOfOrNull { it.pending.start }
                }
            return listOfNotNull(cache.nextStart(track, start), flight).minOrNull()
        }

        private fun reuseAt(
            start: Long,
            end: Long,
            retainedEndExclusive: Long = end,
        ): Pending? {
            cache.acquire(track, start, end)?.let { lease ->
                val id = plan(start, lease.endExclusive - 1)
                val reused =
                    Block(
                        id,
                        start,
                        lease.bytes,
                        lease.total,
                        lease.offset,
                        (lease.endExclusive - start).toInt(),
                        lease::close,
                        lease.provenance,
                    )
                readyForRead(reused)
                monitor.trace(
                    "cache_hit",
                    "reader" to readerId,
                    "track" to track.kind.name,
                    "start" to start,
                    "end" to lease.endExclusive - 1,
                    "bytes" to reused.length,
                )
                return Pending(
                    id,
                    ResultFuture<Block>().apply { complete(reused) },
                    reused.length.toLong(),
                    start,
                    lease::close,
                )
            }
            val retained =
                synchronized(retainedLock) {
                    retainedRanges.firstOrNull { it.track == track && start in it.range }?.also {
                        it.claimed = true
                        retainedRanges.remove(it)
                    }
                } ?: return null
            if (retained.pending.future.isDone) {
                try {
                    retained.pending.future.get()
                } catch (_: Exception) {
                    retained.release()
                    return reuseAt(start, end, retainedEndExclusive)
                }
            }
            // Ownership covers the original request, not just one newly partitioned piece.
            // Prefetch may consume its full remaining range so a later piece cannot redownload
            // the still-running suffix after this claim disappears from the shared registry.
            val until = minOf(retainedEndExclusive, retained.range.last + 1)
            val id = plan(start, until - 1)

            fun view(original: Block): Block =
                Block(
                    id,
                    start,
                    original.bytes,
                    original.total,
                    original.offset + (start - original.start).toInt(),
                    (minOf(until, original.start + original.length) - start).toInt(),
                    { retained.release() },
                    original.provenance,
                )
            retained.onReady = { original -> if (!cancelled && !detached) readyForRead(view(original)) }
            // Cover completion immediately before the callback was attached.
            retained.owner.completedBlocks[retained.pending.start]?.let { retained.onReady?.invoke(it) }
            val future =
                object : Future<Block> {
                    private fun await(load: () -> Block): Block {
                        // The retained coordinator owns the sockets, so lend it this playback demand
                        // while the new reader waits. Rescue ownership and lazy CDN priority stay live.
                        val owner = retained.owner
                        owner.awaitedRange = retained.range
                        owner.awaitedWork = retained.pending.future
                        return try {
                            view(load())
                        } finally {
                            if (owner.awaitedWork === retained.pending.future) {
                                owner.awaitedRange = null
                                owner.awaitedWork = null
                            }
                        }
                    }

                    override fun get(): Block = await { retained.pending.future.get() }

                    override fun get(
                        timeout: Long,
                        unit: TimeUnit,
                    ): Block = await { retained.pending.future.get(timeout, unit) }

                    override fun isDone(): Boolean = retained.pending.future.isDone

                    override fun isCancelled(): Boolean = retained.pending.future.isCancelled

                    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
                        val cancelled = retained.pending.future.cancel(mayInterruptIfRunning)
                        retained.release()
                        return cancelled
                    }
                }
            monitor.trace(
                "seek_reuse_inflight",
                "reader" to readerId,
                "track" to track.kind.name,
                "start" to start,
                "end" to until - 1,
                "originalStart" to retained.range.first,
                "originalEnd" to retained.range.last,
            )
            return Pending(id, future, until - start, start, { retained.release() }, retained = retained)
        }

        private fun readyForRead(result: Block) {
            monitor.rangeReady(track.kind, result.start, result.start + result.length, readinessEpoch)
            monitor.progress(result.id, result.length.toLong())
            monitor.state(result.id, DownloadBlockState.Complete)
        }

        @Volatile private var lastAdmissionRangeBytes = 0L

        @Volatile private var lastAdmissionHeadroomBytes = 0L

        fun hasReservation(start: Long): Boolean = payloadReservations.containsKey(start)

        fun hasPendingRetirement(): Boolean = retiringRanges.any { payloadReservations.containsKey(it) }

        fun protects(
            start: Long,
            end: Long,
        ): Boolean =
            synchronized(pending) {
                start <= position ||
                    block?.let { it.start < end && it.start + it.length > start } == true ||
                    awaitedPending?.let { it.start < end && it.start + it.length > start } == true ||
                    pending.peek()?.let { it.start < end && it.start + it.length > start } == true
            }

        fun recoveryVictims(): List<Pair<RangeDataSource, Pending>> =
            synchronized(pending) {
                pending
                    .filter { !protects(it.start, it.start + it.length) && it.retained == null }
                    .sortedBy { it.future.isDone }
                    .map { this to it }
            }

        fun retireForMemory(victim: Pending): Boolean =
            synchronized(pending) {
                if (victim !in pending ||
                    protects(victim.start, victim.start + victim.length)
                ) {
                    return@synchronized false
                }
                if (cache.evictionCandidates().any {
                        it.track == track &&
                            it.pinned &&
                            it.start < victim.start + victim.length &&
                            it.endExclusive > victim.start
                    }
                ) {
                    return@synchronized false
                }
                revokedBlocks.add(victim.id)
                retiringRanges.add(victim.start)
                pending.remove(victim)
                workKeys.remove(victim.start)?.let { plannedWork.remove(it) }
                liveTraceAttempts
                    .filter { attempt ->
                        attempt.range?.let {
                            it.first >= victim.start && it.last < victim.start + victim.length
                        } == true
                    }.forEach { it.cancelMeasurement() }
                victim.future.cancel(true)
                victim.release()
                cache.evictUnread(track, victim.start, victim.start + victim.length)
                val ranges =
                    (
                        plannedRanges.toList() +
                            listOf(
                                victim.start until (victim.start + victim.length),
                            )
                    ).sortedBy { it.first }
                plannedRanges.clear()
                plannedRanges.addAll(ranges)
                ids.remove(victim.id)
                monitor.discard(listOf(victim.id))
                true
            }

        private fun reservePayload(
            start: Long,
            bytes: Long,
            demandHeadroom: Long = 0,
        ): Boolean {
            lastAdmissionRangeBytes = bytes
            lastAdmissionHeadroomBytes = demandHeadroom
            if (!memoryBudget.tryReservePayload(bytes, demandHeadroom)) {
                val needed =
                    (
                        memoryBudget.snapshot().sharedUsedBytes + bytes * 2 + demandHeadroom -
                            memoryBudget.sharedLimitBytes
                    ).coerceAtLeast(1)
                val reclaimed = cache.reclaimEligible(needed)
                if (reclaimed > 0) {
                    monitor.trace("cache_reclaim", "bytes" to reclaimed, "reason" to "admission_pressure")
                }
                if (!memoryBudget.tryReservePayload(bytes, demandHeadroom)) {
                    if (start == position && block == null && !cancelled && !detached) {
                        memoryBudget.claimRecovery(memoryDemandKey, bytes)
                        if (httpAdmission.freeSlots > 0) {
                            reclaimForRecovery(memoryDemandKey, bytes, this, start)
                            if (memoryBudget.tryReserveRecoveryPayload(memoryDemandKey, bytes)) {
                                recoveryPayloadKeys[start] = memoryDemandKey
                                payloadReservations[start] = bytes
                                memoryDeniedSince = 0
                                return true
                            }
                        }
                        if (memoryDeniedSince == 0L) memoryDeniedSince = System.nanoTime()
                    }
                    val shared = memoryBudget.snapshot()
                    val reason =
                        if (bytes * 2 <= shared.sharedLimitBytes - shared.sharedUsedBytes) {
                            "shared_byte_headroom"
                        } else {
                            "shared_byte_watermark"
                        }
                    monitor.schedulerWait(reason)
                    traceScheduler(reason)
                    return false
                }
            }
            payloadReservations[start] = bytes
            if (start == position) {
                memoryDeniedSince = 0
                memoryBudget.releaseRecoveryClaim(memoryDemandKey)
            }
            return true
        }

        private fun releasePayload(start: Long) {
            completedBlocks.remove(start)
            synchronized(pending) {
                if ((payloadUsers[start] ?: 0) > 0) {
                    releasedPayloads.add(start)
                } else if (start in protectedPayloads) {
                    consumedProtectedPayloads.add(start)
                } else {
                    payloadReservations.remove(start)?.let { bytes ->
                        payloadProvenance.remove(start)?.close()
                        memoryBudget.releasePayload(
                            bytes,
                            payloadStorage.remove(start),
                            recoveryPayloadKeys.remove(start),
                        )
                    }
                }
            }
        }

        // Cancellation only requests release. A socket may still own its response array until
        // it acknowledges cancellation; keep that memory charged through its finally block.
        private fun retainPayload(start: Long): Long? =
            synchronized(pending) {
                val owner =
                    payloadReservations.entries
                        .firstOrNull {
                            start >= it.key && start - it.key < it.value && it.key !in releasedPayloads
                        }?.key ?: return@synchronized null
                payloadUsers[owner] = (payloadUsers[owner] ?: 0) + 1
                owner
            }

        private fun finishPayloadUser(owner: Long) =
            synchronized(pending) {
                val remaining = requireNotNull(payloadUsers[owner]) - 1
                if (remaining > 0) {
                    payloadUsers[owner] = remaining
                } else {
                    payloadUsers.remove(owner)
                    if (releasedPayloads.remove(owner)) releasePayload(owner)
                }
            }

        private fun finishProtectedPayload(
            start: Long,
            token: Long,
        ) {
            synchronized(pending) {
                if (generation != token) return
                protectedPayloads.remove(start)
                if (consumedProtectedPayloads.remove(start)) releasePayload(start)
            }
        }

        private fun waitForPlanning() {
            checkReadOpen()
            val denied = memoryDeniedSince
            if (denied != 0L && System.nanoTime() - denied >= TimeUnit.SECONDS.toNanos(30)) {
                traceScheduler("recovery_capacity_timeout")
                memoryBudget.releaseRecoveryClaim(memoryDemandKey)
                throw DownloadCapacityException("Immediate playback demand has no safe memory capacity")
            }
            try {
                Thread.sleep(25)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Download capacity wait interrupted")
            }
        }

        private fun plan(
            start: Long,
            end: Long,
        ): Long = monitor.plan(track.kind, start, end).also { ids.add(it) }

        // The browser implementation receives one SIDX segment per outer request. Media3
        // may request the entire file, so recreate those segment boundaries before splitting.
        private fun fillWindow() = fillReadAhead()

        fun pendingBlockCount(): Int =
            synchronized(pending) {
                pending.size + if (awaitedPending != null) 1 else 0
            }

        fun admissionCandidate(): ReadAheadAdmission.Candidate? =
            synchronized(pending) {
                if (closed ||
                    cancelled ||
                    detached ||
                    !admissionReady ||
                    total == null ||
                    sequential != null ||
                    (plannedRanges.isEmpty() && nextStart >= endExclusive)
                ) {
                    return@synchronized null
                }
                val next = plannedRanges.peek()?.first ?: nextStart
                val preparedEnd =
                    maxOf(
                        position,
                        block?.let { it.start + it.length } ?: position,
                        pending.maxOfOrNull { it.start + it.length } ?: position,
                        awaitedPending?.let { it.start + it.length } ?: position,
                    )
                ReadAheadAdmission.Candidate(
                    readerId,
                    monitor.schedulingTimeMs(track.kind, preparedEnd),
                    monitor.schedulingTimeMs(track.kind, position),
                    monitor.schedulingTimeMs(track.kind, next),
                    pendingBlockCount(),
                    block == null && pending.isEmpty() && awaitedPending == null,
                )
            }

        fun headCharge(): Pair<DownloadTrack, Long>? =
            synchronized(pending) {
                if (cancelled || detached || !admissionReady) return@synchronized null
                val bytes = block?.length?.toLong() ?: awaitedPending?.length ?: pending.peek()?.length ?: 0L
                track.kind to bytes * 2
            }

        fun admitOne(headCharges: Map<DownloadTrack, Long>): Boolean {
            synchronized(pending) {
                if (closed || cancelled || detached) return false
                val staged = mutableListOf<StagedRange>()
                var memoryBlocked = false
                var coordinatorBlocked = false
                while (!cancelled &&
                    staged.isEmpty() &&
                    (!startupScheduling() || pending.isEmpty() && awaitedPending == null) &&
                    (plannedRanges.isNotEmpty() || nextStart < endExclusive)
                ) {
                    if (plannedRanges.isEmpty()) {
                        val segment = monitor.segmentBounds(track.kind, nextStart)
                        val spanEnd =
                            if (segment == null) {
                                minOf(
                                    endExclusive - 1,
                                    nextStart +
                                        minOf(
                                            maxOf(RangePartition.UNKNOWN_INDEX_BYTES, minimumBlockBytes),
                                            memoryBudget.planningSpanLimitBytes,
                                        ) -
                                        1,
                                )
                            } else {
                                minOf(
                                    endExclusive - 1,
                                    segment.last,
                                    nextStart + memoryBudget.planningSpanLimitBytes - 1,
                                )
                            }
                        val pieces =
                            if (segment == null) {
                                listOf(nextStart..spanEnd)
                            } else {
                                RangePartition.split(
                                    nextStart,
                                    spanEnd,
                                    RangePartition.pieceBudget(limit, startupPartition, track.kind),
                                    minimumBlockBytes,
                                )
                            }
                        if (segment != null) startupPartition = false
                        plannedRanges.addAll(pieces)
                        nextStart = spanEnd + 1
                    }
                    var range = plannedRanges.peek() ?: break
                    if (range.first in retiringRanges) {
                        if (payloadReservations.containsKey(range.first)) break
                        retiringRanges.remove(range.first)
                    }
                    val reused = reuseAt(range.first, range.last + 1, retainedEndExclusive = endExclusive)
                    if (reused != null) {
                        plannedRanges.remove()
                        val next = range.first + reused.length
                        if (next <= range.last) {
                            plannedRanges.addFirst(next..range.last)
                        } else {
                            // One retained request can span several new pieces or planning spans.
                            // Drop only its covered bytes, preserving any unclaimed suffix in order.
                            while (plannedRanges.isNotEmpty() && plannedRanges.peek().first < next) {
                                val covered = plannedRanges.remove()
                                if (covered.last >= next) {
                                    plannedRanges.addFirst(next..covered.last)
                                    break
                                }
                            }
                            nextStart = maxOf(nextStart, next)
                        }
                        staged.add(StagedRange(range.first until next, reused.id, reused = reused))
                        continue
                    }
                    nextReusableStart(range.first)?.takeIf { it <= range.last }?.let { boundary ->
                        plannedRanges.remove()
                        plannedRanges.addFirst(boundary..range.last)
                        range = range.first..(boundary - 1)
                        plannedRanges.addFirst(range)
                    }
                    val length = range.last - range.first + 1
                    // Account the real partition shape, including any larger body still owned.
                    memoryBudget.observeBlock(track.kind, maxOf(length, payloadReservations.values.maxOrNull() ?: 0L))
                    val chargedHeads = headCharges + (track.kind to maxOf(headCharges[track.kind] ?: 0L, length * 2))
                    val demandHeadroom = memoryBudget.demandReserve(chargedHeads)
                    if (!reservePayload(range.first, length, demandHeadroom)) {
                        memoryBlocked = true
                        break
                    }
                    plannedRanges.remove()
                    val id = plan(range.first, range.last)
                    val key = Any()
                    workKeys[range.first] = key
                    plannedWork[key] = PlannedWork(track.kind, range.first, range.last)
                    staged.add(StagedRange(range, id, key))
                }
                // Register the entire window before any worker may explore. Only its
                // farthest planned block can lend useful work to an unknown route.
                for ((index, work) in staged.withIndex()) {
                    val (range, id) = work
                    work.reused?.let {
                        pending.add(it)
                    }
                    if (work.reused != null) continue
                    val token = generation
                    val queuedAt = System.nanoTime()
                    val future =
                        executor.trySubmit(
                            priority = {
                                BoundedDownloadExecutor.Priority(
                                    urgent = ordinaryUrgency(range.first) != null,
                                    mediaTimeMs = monitor.schedulingTimeMs(track.kind, range.first),
                                )
                            },
                            callable =
                                Callable {
                                    monitor.trace(
                                        "coordinator_start",
                                        "reader" to readerId,
                                        "block" to id,
                                        "track" to track.kind.name,
                                        "queueMs" to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - queuedAt),
                                    )
                                    fetch(range.first, range.last, id, token)
                                },
                            onComplete = { rangeFinished(range.first, it) },
                        )
                    if (future == null) {
                        // A full short queue defers planning, not playback or the range itself.
                        // Restore the unsubmitted suffix in byte order and release its reservations.
                        coordinatorBlocked = true
                        monitor.schedulerWait("coordinator_queue")
                        for ((deferred, deferredId, key, reused) in staged.drop(index).asReversed()) {
                            plannedRanges.addFirst(deferred)
                            if (key != null) {
                                plannedWork.remove(key)
                                workKeys.remove(deferred.first, key)
                            }
                            ids.remove(deferredId)
                            monitor.discard(listOf(deferredId))
                            if (reused?.retained != null) {
                                val retained = reused.retained
                                retained.offerAgain()
                                retained.owner.rangeFinished(retained.pending.start, retained.pending.future)
                            } else if (reused != null) {
                                reused.release()
                            } else {
                                releasePayload(deferred.first)
                            }
                        }
                        break
                    }
                    pending.add(
                        Pending(id, future, range.last - range.first + 1, range.first, { releasePayload(range.first) }),
                    )
                    monitor.trace(
                        "dispatch",
                        "reader" to readerId,
                        "track" to track.kind.name,
                        "block" to id,
                        "start" to range.first,
                        "end" to range.last,
                        "deadlineSlackMs" to
                            monitor.deadlineNanos(track.kind, range.first)?.let {
                                TimeUnit.NANOSECONDS.toMillis(it - System.nanoTime())
                            },
                    )
                }
                val ordered = pending.sortedBy { it.start }
                pending.clear()
                pending.addAll(ordered)
                traceScheduler(
                    when {
                        plannedRanges.isEmpty() && nextStart >= endExclusive -> "all_ranges_dispatched"
                        coordinatorBlocked -> "coordinator_queue_full"
                        memoryBlocked -> "payload_budget"
                        startupScheduling() -> "startup_window"
                        else -> "reader_window"
                    },
                )
                return staged.isNotEmpty() && !coordinatorBlocked
            }
        }

        private fun startupAudioLimited(): Boolean =
            track.kind == DownloadTrack.Audio && source.video != null && startupScheduling()

        private fun acquireStartupAudio(): Boolean {
            while (startupAudioLimited()) {
                checkOpen()
                if (startupAudioPermit.tryAcquire()) return true
                if (startupAudioPermit.tryAcquire(100, TimeUnit.MILLISECONDS)) return true
                traceScheduler("startup_audio_slots")
            }
            return false
        }

        private fun ordinaryUrgency(start: Long): Long? {
            val awaited = awaitedRange ?: return null
            if (start !in awaited || awaitedWork?.isDone == true) return null
            return monitor.schedulingTimeMs(track.kind, start) ?: Long.MIN_VALUE
        }

        private fun acquireOrdinary(
            start: Long,
            token: Long,
            demanded: Boolean = false,
            routeControlled: Boolean = false,
            speculative: Boolean = false,
        ): HttpAdmissionController.Lease {
            val ticket =
                httpAdmission.register(
                    order = {
                        HttpAdmissionController.Order(
                            when {
                                recoveryPayloadKeys.containsKey(start) -> HttpAdmissionController.Priority.Recovery
                                demanded || ordinaryUrgency(start) != null -> HttpAdmissionController.Priority.Demand
                                speculative -> HttpAdmissionController.Priority.Speculative
                                else -> HttpAdmissionController.Priority.Ordinary
                            },
                            deadline =
                                if (recoveryPayloadKeys.containsKey(start)) {
                                    monitor.deadlineNanos(track.kind, start) ?: Long.MAX_VALUE
                                } else {
                                    Long.MAX_VALUE
                                },
                            mediaTime = monitor.schedulingTimeMs(track.kind, start) ?: Long.MIN_VALUE,
                        )
                    },
                    eligible = {
                        !routeControlled ||
                            !resolver.allCooling(track) ||
                            ((demanded || ordinaryUrgency(start) != null) && resolver.recoveryAvailable())
                    },
                )
            monitor.trace("ordinary_queued", "reader" to readerId, "track" to track.kind.name, "start" to start)
            try {
                while (true) {
                    checkOpen()
                    if (generation != token) throw InterruptedIOException("Stale queued media range")
                    val lease = httpAdmission.tryAcquire(ticket)
                    if (lease != null) {
                        try {
                            checkOpen()
                            if (generation != token) throw InterruptedIOException("Stale queued media range")
                        } catch (error: Exception) {
                            lease.close()
                            throw error
                        }
                        monitor.trace(
                            "track_dispatch",
                            "reader" to readerId,
                            "start" to start,
                            "priority" to ticket.order().priority,
                            "globalSlotsFree" to httpAdmission.freeSlots,
                            "ordinarySlotsFree" to httpAdmission.freeOrdinarySlots,
                        )
                        return lease
                    }
                    traceScheduler(
                        when {
                            routeControlled && resolver.allCooling(track) -> "cdn_recovery_wait"
                            httpAdmission.freeSlots == 0 -> "global_slots"
                            else -> "higher_priority_or_ineligible"
                        },
                    )
                    Thread.sleep(25)
                }
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("HTTP admission interrupted")
            } finally {
                httpAdmission.cancel(ticket)
            }
        }

        private inner class Attempt(
            val url: String,
            val ordinary: Boolean,
            val measurementLease: SplitLease? = null,
            var prefix: ByteArray = ByteArray(0),
            val reservedBytes: Long = 0,
        ) {
            var transportLease: HttpAdmissionController.Lease? = null

            @Volatile var partialBody: ByteArray? = null
            val traceId = traceIds.incrementAndGet()
            val lastTraceNanos = AtomicLong()
            var payloadOwner: Long? = null
            var releaseDuplicate: () -> Unit = {}
            val accounting = AttemptAccounting(monitor::discardedTransfer)
            private val provenanceHandoff = ProvenanceHandoff()
            var bodySource: ExactTransferLedger.Source? = null
            var prefixProvenance: ExactTransferLedger.Handle? = null

            fun publishProvenance(handle: ExactTransferLedger.Handle) = provenanceHandoff.publish(handle)

            fun retainResult() {
                accounting.retain()
                provenanceHandoff.take()
            }

            fun discardResult() {
                accounting.discard()
                provenanceHandoff.discard()
            }

            fun releasePrefix() =
                synchronized(this) {
                    prefixProvenance?.close()
                    prefixProvenance = null
                }

            val workKind: DownloadWorkKind
                get() =
                    when {
                        startupTotal != null -> DownloadWorkKind.Probe
                        measurementLease != null -> DownloadWorkKind.Exploration
                        !ordinary -> DownloadWorkKind.Rescue
                        else -> DownloadWorkKind.Ordinary
                    }
            var startupTotal: AtomicLong? = null
            var requiredOpportunityNanos: Long? = null

            @Volatile var rescuePenaltyEligible = false
            var reportBlockState = true

            @Volatile var range: LongRange? = null
            var missReported = false
            var routeAssignment: CdnResolver.RouteAssignment? = null
            var finished = false
            val cancelled = AtomicBoolean(false)
            val cancelMeasurement: () -> Unit = { cancel() }

            @Volatile var call: Call? = null

            @Volatile var received = 0L

            @Volatile var expectedBytes = Long.MAX_VALUE
            val began = System.nanoTime()

            @Volatile var lastProgressNanos = began

            fun cancel() {
                if (!cancelled.get()) {
                    monitor.trace(
                        "attempt_cancel_requested",
                        "reader" to readerId,
                        "attempt" to traceId,
                        "track" to track.kind.name,
                        "host" to url.toHttpUrlOrNull()?.host,
                    )
                }
                cancelled.set(true)
                call?.cancel()
            }

            fun atRisk(
                deadline: Long,
                expected: Long,
                now: Long,
                takeoverNanos: Long,
            ): Boolean {
                val slack = deadline - now
                if (slack <= takeoverNanos + TimeUnit.SECONDS.toNanos(1)) return true
                val elapsed = now - began
                val remaining = (minOf(expected, expectedBytes) - received).coerceAtLeast(0)
                // Short requests inherit cross-request CDN history. A brief silent interval
                // cannot collapse an instantaneous EWMA and trigger an immediate hedge.
                val history = resolver.remainingTransferNanos(url, remaining)
                val observed =
                    if (elapsed >= TimeUnit.SECONDS.toNanos(2) && received > 0) {
                        (remaining.toDouble() * elapsed / received).toLong()
                    } else {
                        null
                    }
                val forecast = listOfNotNull(history, observed).maxOrNull() ?: return false
                return forecast >= slack
            }
        }

        private fun fetchStartupHead(
            start: Long,
            end: Long,
            id: Long,
            token: Long,
        ): Block {
            val candidates =
                resolver
                    .candidates(track, end - start + 1)
                    .distinctBy { it.toHttpUrlOrNull()?.let { parsed -> "${parsed.host}:${parsed.port}" } }
            if (track.id !in listOfNotNull(source.video?.id, source.audio?.id) ||
                monitor.hasStartedPlayback() ||
                resolver.allCooling(track) ||
                !startupProbed.add(track.id)
            ) {
                return fetch(start, end, id, token)
            }
            startupPolicy.register(track.kind, candidates.size)
            if (candidates.isEmpty()) {
                startupPolicy.endTrack(track.kind)
                publishStartup()
                return fetch(start, end, id, token)
            }
            publishStartup()
            val queue = ArrayDeque(candidates)
            val first = ResultFuture<Block>()
            val firstProvenance = ProvenanceHandoff()
            // Startup probes have their own lifecycle: unlike protected exploration,
            // unfinished duplicates are cancelled as soon as Media3 is ready.
            startupExecutor.execute {
                val attempts = mutableSetOf<Attempt>()
                val results = LinkedBlockingQueue<Pair<Attempt, Result<Block>>>()
                var acceptingProbeResults = true
                val startupTotal = AtomicLong(-1)
                var lastError: IOException = IOException("No startup CDN succeeded")
                try {
                    monitor.routeCandidates(candidates.mapNotNull { it.toHttpUrlOrNull()?.host })
                    monitor.state(id, DownloadBlockState.Active)
                    while (queue.isNotEmpty() || attempts.isNotEmpty()) {
                        checkOpen()
                        traceScheduler("startup_probe_loop")
                        attempts.forEach { traceAttemptProgress(it) }
                        if (generation != token) throw InterruptedIOException("Stale startup range")
                        if (monitor.hasStartedPlayback() || startupPolicy.isTrackDone(track.kind)) break
                        while (queue.isNotEmpty() && !monitor.hasStartedPlayback()) {
                            while (queue.isNotEmpty() &&
                                resolver.cooldownRemainingMs(queue.first()) > 0
                            ) {
                                queue.removeFirst()
                            }
                            if (queue.isEmpty()) break
                            val probeTicket =
                                httpAdmission.register(order = {
                                    HttpAdmissionController.Order(
                                        HttpAdmissionController.Priority.Speculative,
                                        mediaTime = monitor.schedulingTimeMs(track.kind, start) ?: Long.MIN_VALUE,
                                    )
                                })
                            val probeSlot =
                                try {
                                    httpAdmission.tryAcquire(probeTicket) {
                                        memoryBudget.tryReserveRescue(
                                            end - start + 1,
                                        )
                                    }
                                } finally {
                                    httpAdmission.cancel(probeTicket)
                                }
                            if (probeSlot == null) break
                            if (!startupPolicy.tryAcquire(track.kind)) {
                                memoryBudget.releaseRescue(end - start + 1)
                                probeSlot.close()
                                break
                            }
                            val url = queue.removeFirst()
                            val attempt = Attempt(url, true).also { it.transportLease = probeSlot }
                            attempt.reportBlockState = false
                            attempt.startupTotal = startupTotal
                            attempt.routeAssignment = resolver.requestStarted(url, end - start + 1)
                            attempts.add(attempt)
                            if (first.isDone) startupProbeCancellations.add(attempt.cancelMeasurement)
                            try {
                                requestExecutor.execute(
                                    HttpAttemptTask {
                                        val result = runCatching { fetchAttempt(start, end, id, token, attempt) }
                                        synchronized(results) {
                                            if (acceptingProbeResults) {
                                                results.offer(attempt to result)
                                            } else {
                                                attempt.discardResult()
                                                attempt.releaseDuplicate()
                                            }
                                        }
                                    },
                                )
                            } catch (error: RuntimeException) {
                                attempts.remove(attempt)
                                resolver.requestFinished(requireNotNull(attempt.routeAssignment))
                                probeSlot.close()
                                memoryBudget.releaseRescue(end - start + 1)
                                startupPolicy.abandon(track.kind)
                                throw error
                            }
                        }
                        val completed = results.poll(25, TimeUnit.MILLISECONDS) ?: continue
                        attempts.remove(completed.first)
                        completed.first.releaseDuplicate()
                        startupProbeCancellations.remove(completed.first.cancelMeasurement)
                        val winner = completed.second.getOrNull()
                        startupPolicy.completed(track.kind, winner != null)
                        publishStartup()
                        if (winner != null) {
                            if (!first.isDone) {
                                monitor.recordBytes(track.kind, start, winner.bytes)
                                monitor.progress(id, winner.bytes.size.toLong())
                                monitor.state(id, DownloadBlockState.Complete)
                                attempts.forEach { startupProbeCancellations.add(it.cancelMeasurement) }
                                completed.first.retainResult()
                                winner.provenance?.let(firstProvenance::publish)
                                first.complete(winner)
                            } else {
                                completed.first.discardResult()
                            }
                        } else {
                            completed.first.discardResult()
                            lastError = asIo(requireNotNull(completed.second.exceptionOrNull()))
                        }
                    }
                    if (!first.isDone) first.completeExceptionally(lastError)
                } catch (error: Exception) {
                    first.completeExceptionally(error)
                } finally {
                    synchronized(results) {
                        acceptingProbeResults = false
                        while (true) {
                            val completed = results.poll() ?: break
                            completed.first.discardResult()
                            completed.first.releaseDuplicate()
                            if (attempts.remove(completed.first)) {
                                startupProbeCancellations.remove(completed.first.cancelMeasurement)
                                startupPolicy.completed(track.kind, completed.second.isSuccess)
                            }
                        }
                    }
                    startupPolicy.endTrack(track.kind)
                    attempts.forEach {
                        startupProbeCancellations.remove(it.cancelMeasurement)
                        it.discardResult()
                        it.cancel()
                        startupPolicy.completed(track.kind, false)
                    }
                    publishStartup()
                }
            }
            try {
                return first.get().also { firstProvenance.take() }
            } catch (error: ExecutionException) {
                // Readiness can race with a new reader; required bytes still load normally.
                if (monitor.hasStartedPlayback() || resolver.allCooling(track)) return fetch(start, end, id, token)
                throw asIo(error.cause ?: error)
            } finally {
                firstProvenance.discard()
            }
        }

        // Whole-block experiments get an earlier takeover than the larger half-block races.
        private fun explorationSafetyNanos(bytes: Long): Long =
            TimeUnit.SECONDS.toNanos(
                if (bytes < maxOf(RangePartition.MIN_EXPLORATION_BYTES, minimumBlockBytes * 2)) 2 else 1,
            )

        private fun fetch(
            start: Long,
            end: Long,
            id: Long,
            token: Long,
        ): Block =
            try {
                rememberBlock(fetchNetwork(start, end, id, token), token)
            } catch (error: Exception) {
                forgetFailedRange(start)
                throw error
            }

        private fun fetchNetwork(
            start: Long,
            end: Long,
            id: Long,
            token: Long,
        ): Block {
            synchronized(admissionLock) { /* Observe the complete admitted batch before exploration. */ }
            val key = workKeys[start]
            try {
                val now = System.nanoTime()
                val deadline = monitor.deadlineNanos(track.kind, start)
                val skipReason =
                    when {
                        key == null -> "not_planned"
                        startupScheduling() -> "startup"
                        monitor.isPlaybackPaused() -> "paused"
                        urgentRanges.isNotEmpty() -> "urgent_work"
                        splitActive.get() -> "exploration_active"
                        deadline == null -> "no_deadline"
                        deadline - now <= TimeUnit.SECONDS.toNanos(3) -> "insufficient_slack"
                        else -> null
                    }
                if (skipReason != null) {
                    monitor.trace(
                        "exploration_gate",
                        "reader" to readerId,
                        "block" to id,
                        "track" to track.kind.name,
                        "eligible" to false,
                        "reason" to skipReason,
                        "evaluationMs" to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - now),
                    )
                    return fetchSingle(start, end, id, token)
                }
                val known = resolver.candidates(track, end - start + 1).firstOrNull()
                val estimate = known?.let { resolver.estimatedTransferNanos(it, end - start + 1) }
                if (estimate == null ||
                    requireNotNull(deadline) - System.nanoTime() <=
                    estimate + explorationSafetyNanos(end - start + 1)
                ) {
                    monitor.trace(
                        "exploration_gate",
                        "reader" to readerId,
                        "block" to id,
                        "track" to track.kind.name,
                        "eligible" to false,
                        "reason" to "backup_margin",
                        "evaluationMs" to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - now),
                    )
                    return fetchSingle(start, end, id, token)
                }
                // Search from farthest to nearest and stop at the first eligible range.
                // The old path ranked every CDN for every planned block on every dispatch,
                // serializing workers through the resolver even when exploration was impossible.
                val farthest =
                    plannedWork.entries
                        .mapNotNull { entry ->
                            monitor.schedulingTimeMs(entry.value.track, entry.value.start)?.let {
                                Triple(entry.key, entry.value, it)
                            }
                        }.sortedWith(
                            compareByDescending<Triple<Any, PlannedWork, Long>> { it.third }
                                .thenByDescending { it.second.start },
                        ).firstOrNull { (_, work, _) ->
                            val workTrack =
                                when (work.track) {
                                    track.kind -> track
                                    DownloadTrack.Video -> source.video
                                    DownloadTrack.Audio -> source.audio
                                } ?: return@firstOrNull false
                            val slack =
                                monitor.deadlineNanos(work.track, work.start)?.minus(System.nanoTime())
                                    ?: return@firstOrNull false
                            if (slack <= TimeUnit.SECONDS.toNanos(3)) return@firstOrNull false
                            val bytes = work.end - work.start + 1
                            val route =
                                resolver.candidates(workTrack, bytes).firstOrNull()
                                    ?: return@firstOrNull false
                            val forecast =
                                resolver.estimatedTransferNanos(route, bytes)
                                    ?: return@firstOrNull false
                            slack > forecast + explorationSafetyNanos(bytes)
                        }?.first
                val eligible =
                    key != null &&
                        key === farthest &&
                        deadline != null &&
                        deadline - System.nanoTime() > TimeUnit.SECONDS.toNanos(3) &&
                        estimate != null &&
                        deadline - System.nanoTime() > estimate + explorationSafetyNanos(end - start + 1) &&
                        urgentRanges.isEmpty() &&
                        !monitor.isPlaybackPaused() &&
                        !startupScheduling()
                if (monitor.traceEnabled) {
                    monitor.trace(
                        "exploration_gate",
                        "reader" to readerId,
                        "block" to id,
                        "track" to track.kind.name,
                        "eligible" to eligible,
                        "isFarthestEligible" to (key != null && key === farthest),
                        "hasEligibleFutureBlock" to (farthest != null),
                        "deadlineSlackMs" to deadline?.let { TimeUnit.NANOSECONDS.toMillis(it - now) },
                        "backupEstimateMs" to estimate?.let { TimeUnit.NANOSECONDS.toMillis(it) },
                        "safetyMs" to TimeUnit.NANOSECONDS.toMillis(explorationSafetyNanos(end - start + 1)),
                        "urgentRanges" to urgentRanges.size,
                        "paused" to monitor.isPlaybackPaused(),
                        "startup" to startupScheduling(),
                        "explorationAlreadyActive" to splitActive.get(),
                        "evaluationMs" to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - now),
                    )
                }
                if (!eligible || !splitActive.compareAndSet(false, true)) return fetchSingle(start, end, id, token)
                synchronized(pending) { protectedPayloads.add(start) }
                val lease = SplitLease { finishProtectedPayload(start, token) }
                val futures = mutableListOf<Future<Block>>()
                val childProvenance = mutableListOf<ExactTransferLedger.Handle>()
                var acceptingChildren = true

                fun keepChild(result: Block): Block {
                    synchronized(childProvenance) {
                        result.provenance?.let {
                            if (acceptingChildren) childProvenance.add(it) else it.close()
                        }
                    }
                    return result
                }
                val children = mutableListOf<Long>()
                var completedSplit = false
                try {
                    val good = requireNotNull(known)
                    val candidate =
                        resolver.explorationCandidate(track, good) ?: run {
                            monitor.trace(
                                "exploration_skipped",
                                "reader" to readerId,
                                "block" to id,
                                "track" to track.kind.name,
                                "reason" to "no_eligible_candidate",
                            )
                            return fetchSingle(start, end, id, token)
                        }
                    if (end - start + 1 < maxOf(RangePartition.MIN_EXPLORATION_BYTES, minimumBlockBytes * 2)) {
                        // A short segment is a useful whole-range sample; do not split it
                        // into tiny probes merely to fit the ordinary worker partition.
                        monitor.exploration(id, ExplorationState.Testing)
                        val result =
                            fetchSingle(
                                start,
                                end,
                                id,
                                token,
                                candidate,
                                good,
                                lease,
                                explorationMarginNanos = explorationSafetyNanos(end - start + 1),
                            )
                        completedSplit = true
                        return result
                    }
                    val midpoint = start + (end - start + 1) / 2
                    monitor.discard(listOf(id))
                    val firstId = plan(start, midpoint - 1).also { children.add(it) }
                    val secondId = plan(midpoint, end).also { children.add(it) }
                    monitor.exploration(secondId, ExplorationState.Testing)
                    if (BuildConfig.DEBUG) {
                        runCatching {
                            Log.d(
                                "BvRange",
                                "${track.kind} split reason=explore host=${candidate.toHttpUrlOrNull()?.host} " +
                                    "bytes=${end - midpoint + 1}",
                            )
                        }
                    }
                    futures.add(
                        splitExecutor.submit(
                            Callable { keepChild(fetchSingle(start, midpoint - 1, firstId, token, good)) },
                        ),
                    )
                    futures.add(
                        splitExecutor.submit(
                            Callable {
                                keepChild(fetchSingle(midpoint, end, secondId, token, candidate, good, lease))
                            },
                        ),
                    )
                    val first = futures[0].get()
                    val second = futures[1].get()
                    checkOpen()
                    if (token != generation) throw InterruptedIOException("Stale split range")
                    if (first.total != second.total ||
                        first.bytes.size.toLong() != midpoint - start ||
                        second.bytes.size.toLong() != end - midpoint + 1
                    ) {
                        throw IOException("Split range mismatch")
                    }
                    val joined = ByteArray(first.bytes.size + second.bytes.size)
                    first.bytes.copyInto(joined)
                    second.bytes.copyInto(joined, first.bytes.size)
                    // The second half may finish first; recording the complete ordered
                    // piece preserves the monitor's contiguous MP4-index prefix.
                    monitor.recordBytes(track.kind, start, joined)
                    completedSplit = true
                    val provenance = transferLedger.concat(listOfNotNull(first.provenance, second.provenance))
                    return Block(id, start, joined, first.total, provenance = provenance)
                } catch (error: Exception) {
                    lease.cancel()
                    throw asIo(if (error is ExecutionException) error.cause ?: error else error)
                } finally {
                    synchronized(childProvenance) {
                        acceptingChildren = false
                        childProvenance.forEach { it.close() }
                        childProvenance.clear()
                    }
                    futures.forEach { it.cancel(true) }
                    splitExecutor.purge()
                    if (!completedSplit) monitor.discard(children)
                    children.forEach { ids.remove(it) }
                    lease.release()
                }
            } finally {
                if (key != null) {
                    plannedWork.remove(key)
                    workKeys.remove(start, key)
                }
            }
        }

        private fun fetchSingle(
            start: Long,
            end: Long,
            id: Long,
            token: Long,
            preferredUrl: String? = null,
            rescueUrl: String? = null,
            measurementLease: SplitLease? = null,
            explorationMarginNanos: Long = TimeUnit.SECONDS.toNanos(1),
        ): Block {
            fun ensureCurrent() {
                checkOpen()
                if (token != generation || id in revokedBlocks) throw InterruptedIOException("Stale media range")
            }
            val readyEpoch = readinessEpoch
            val audioPermit = acquireStartupAudio()
            val urgencyKey = Any()
            val rescueKey = Any()
            var acquiredSlot: HttpAdmissionController.Lease? = null
            var rescueTicket: HttpAdmissionController.Ticket? = null
            val tried = concurrentSet<String>()
            var rescueEligible = false
            var rescueExploration = false

            fun routeAvailable(): Boolean =
                if (rescueExploration) {
                    resolver.hasRecoveryExplorationCandidate(track, tried)
                } else {
                    resolver.hasRescueCandidate(track, tried)
                }

            fun acquireRescue(
                bytes: Long,
                exploration: Boolean = false,
            ): Boolean {
                rescueExploration = exploration
                rescueEligible = routeAvailable()
                if (!rescueEligible) {
                    rescueTicket?.let(httpAdmission::cancel)
                    rescueTicket = null
                    memoryBudget.releaseRecoveryClaim(rescueKey)
                    traceRescueWait("no_eligible_cdn", bytes)
                    return false
                }
                val ticket =
                    rescueTicket ?: httpAdmission
                        .register(
                            order = {
                                HttpAdmissionController.Order(
                                    HttpAdmissionController.Priority.Recovery,
                                    monitor.deadlineNanos(track.kind, start) ?: Long.MAX_VALUE,
                                    monitor.schedulingTimeMs(track.kind, start) ?: Long.MIN_VALUE,
                                )
                            },
                            eligible = {
                                rescueEligible &&
                                    !closed &&
                                    !cancelled &&
                                    token == generation &&
                                    routeAvailable() &&
                                    memoryBudget.canReserveRescue(bytes, rescueKey)
                            },
                        ).also { rescueTicket = it }
                if (!httpAdmission.canDispatch(ticket, ignoreEligibility = true)) {
                    traceRescueWait("global_slots_or_priority", bytes)
                    return false
                }
                memoryBudget.claimRecovery(rescueKey, bytes)
                val slot = httpAdmission.tryAcquire(ticket) { memoryBudget.tryReserveRescue(bytes, rescueKey) }
                if (slot == null) {
                    reclaimForRecovery(rescueKey, bytes, this, start)
                    traceRescueWait("recovery_memory", bytes)
                    return false
                }
                rescueTicket = null
                acquiredSlot = slot
                return true
            }

            fun releaseRescue(bytes: Long) {
                acquiredSlot?.close()
                acquiredSlot = null
                memoryBudget.releaseRescue(bytes)
                rescueEligible = false
                rescueTicket?.let(httpAdmission::cancel)
                rescueTicket = null
                memoryBudget.releaseRecoveryClaim(rescueKey)
            }

            fun isFirstBlockingRange(): Boolean {
                val awaited = awaitedRange ?: return false
                if (start > awaited.last || end < awaited.first || awaitedWork?.isDone == true) return false
                rescueFocus.get()?.let { return it === rescueKey }
                val firstReader =
                    readers
                        .filter { reader ->
                            reader.awaitedRange != null && reader.awaitedWork?.isDone != true && !reader.cancelled
                        }.minWithOrNull(
                            compareBy<RangeDataSource> { reader ->
                                reader.awaitedRange?.let { monitor.schedulingTimeMs(reader.track.kind, it.first) }
                                    ?: Long.MIN_VALUE
                            }.thenBy { it.readerId },
                        )
                return firstReader === this
            }

            fun claimRescueFocus(): Boolean {
                if (!isFirstBlockingRange()) return false
                if (rescueFocus.get() === rescueKey) return true
                val claimed = rescueFocus.compareAndSet(null, rescueKey)
                if (claimed) {
                    monitor.trace(
                        "rescue_focus",
                        "reader" to readerId,
                        "block" to id,
                        "track" to track.kind.name,
                        "start" to start,
                        "end" to end,
                        "state" to "claimed",
                    )
                }
                return claimed
            }

            val results = LinkedBlockingQueue<Pair<Attempt, Result<Block>>>()
            var acceptingResults = true
            val attempts = mutableListOf<Attempt>()
            var lastError: IOException = IOException("No eligible CDN route")
            var unsupported: UnsupportedRange? = null
            var active = 0
            var hedged = false
            var displayedProgress = 0L
            var lastRescueLaunch = Long.MIN_VALUE
            var emergencyUsed = false
            var lastDecisionTrace = 0L
            val candidates = resolver.candidates(track, end - start + 1, monitor.deadlineNanos(track.kind, start))
            monitor.routeCandidates(candidates.mapNotNull { it.toHttpUrlOrNull()?.host }.distinct())

            fun launch(
                url: String,
                ordinary: Boolean,
                reservation: CdnResolver.RouteAssignment? = null,
                recoveryLease: SplitLease? = null,
                prefix: RescuePrefix = RescuePrefix(),
            ) {
                val retained =
                    recoveryLease ?: measurementLease?.takeIf { ordinary && tried.isEmpty() && url == preferredUrl }
                val attempt =
                    Attempt(url, ordinary, retained, prefix.bytes, end - start + 1).also {
                        it.prefixProvenance = prefix.provenance
                    }
                attempt.transportLease = requireNotNull(acquiredSlot)
                acquiredSlot = null
                attempt.expectedBytes = end - start + 1 - prefix.bytes.size
                if (recoveryLease != null) attempt.reportBlockState = false
                // Estimate before reserving this request so its bytes are not counted twice.
                attempt.requiredOpportunityNanos =
                    (
                        if (reservation != null) {
                            reservation.estimatedTransferNanos
                        } else {
                            resolver.estimatedTransferNanos(url, end - start + 1 - prefix.bytes.size)
                        }
                    )?.coerceAtLeast(TimeUnit.SECONDS.toNanos(1))
                if (retained != null && !retained.retain(attempt.cancelMeasurement)) {
                    attempt.releasePrefix()
                    attempt.transportLease?.close()
                    if (!ordinary) memoryBudget.releaseRescue(end - start + 1)
                    reservation?.let(resolver::requestFinished)
                    throw InterruptedIOException("Split measurement cancelled")
                }
                if (ordinary) {
                    attempt.payloadOwner = retainPayload(start)
                    if (attempt.payloadOwner == null) {
                        attempt.releasePrefix()
                        attempt.transportLease?.close()
                        retained?.release(attempt.cancelMeasurement)
                        reservation?.let(resolver::requestFinished)
                        throw InterruptedIOException("Range reservation released")
                    }
                }
                attempt.routeAssignment =
                    reservation ?: resolver.requestStarted(url, end - start + 1 - prefix.bytes.size)
                attempts.add(attempt)
                tried.add(url)
                active++
                try {
                    requestExecutor.execute(
                        HttpAttemptTask {
                            val result =
                                runCatching { fetchAttempt(start + prefix.bytes.size, end, id, token, attempt) }
                            synchronized(results) {
                                // A retained measurement updates the pool inside fetchAttempt. Once
                                // playback has a winner, discard its body rather than retaining it in
                                // an abandoned result queue until every other measurement finishes.
                                if (acceptingResults) {
                                    results.offer(attempt to result)
                                } else {
                                    attempt.discardResult()
                                    attempt.releaseDuplicate()
                                }
                            }
                        },
                    )
                } catch (error: RuntimeException) {
                    attempt.releasePrefix()
                    attempt.transportLease?.close()
                    if (!ordinary) memoryBudget.releaseRescue(end - start + 1)
                    attempt.payloadOwner?.let(::finishPayloadUser)
                    retained?.release(attempt.cancelMeasurement)
                    resolver.requestFinished(requireNotNull(attempt.routeAssignment))
                    throw IOException("Range executor closed", error)
                }
            }

            fun recordMiss(
                attempt: Attempt,
                emergency: Boolean = false,
                buffering: Boolean = false,
            ) {
                val assignment = attempt.routeAssignment ?: return
                val fairObservation =
                    emergency &&
                        attempt.requiredOpportunityNanos?.let {
                            System.nanoTime() - attempt.began >= maxOf(it, TimeUnit.SECONDS.toNanos(2))
                        } == true
                val penalize = attempt.rescuePenaltyEligible || fairObservation
                if (buffering && (!penalize || assignment.penaltyLevel >= 2)) return
                resolver.rescued(assignment, penalize, buffering)
                val host =
                    attempt.url
                        .toHttpUrlOrNull()
                        ?.host
                        .orEmpty()
                if (!attempt.missReported) {
                    attempt.missReported = true
                    monitor.routeDeadlineMiss(host, resolver.schedulingConfidence(attempt.url))
                } else {
                    monitor.routeConfidence(host, resolver.schedulingConfidence(attempt.url))
                }
            }

            try {
                while (true) {
                    ensureCurrent()
                    traceScheduler("range_coordinator")
                    attempts.filter { !it.finished }.forEach { traceAttemptProgress(it) }
                    if (active == 0) {
                        // A failed original must not queue behind its own now-obsolete rescue.
                        rescueEligible = false
                        rescueTicket?.let(httpAdmission::cancel)
                        rescueTicket = null
                        memoryBudget.releaseRecoveryClaim(rescueKey)
                        if (unsupported != null) break
                        if (tried.size >= if (emergencyUsed) maxOf(MAX_ATTEMPTS, limit / 2) else MAX_ATTEMPTS) break
                        // Exhausted failures must terminate even when this detached/speculative
                        // range has no current reader demand to qualify for outage recovery.
                        if (!resolver.hasUntriedRoute(track, tried)) break
                        acquiredSlot =
                            acquireOrdinary(
                                start,
                                token,
                                routeControlled = true,
                                speculative = measurementLease != null,
                            )
                        val selectionBegan = System.nanoTime()
                        // The coordinator already owns both permits. Central selection and
                        // reservation are atomic, so queued work never holds a stale route choice.
                        val reservation =
                            if (tried.isEmpty() &&
                                preferredUrl != null &&
                                resolver.cooldownRemainingMs(preferredUrl) == 0L
                            ) {
                                resolver.requestStarted(preferredUrl, end - start + 1)
                            } else {
                                resolver.reserveOrdinary(
                                    track,
                                    end - start + 1,
                                    monitor.deadlineNanos(track.kind, start),
                                    tried,
                                    allowRecovery = ordinaryUrgency(start) != null,
                                )
                            }
                        if (reservation == null) {
                            acquiredSlot?.close()
                            acquiredSlot = null
                            if (resolver.waitingForRecovery(track, tried)) {
                                monitor.schedulerWait("cdn_recovery_wait")
                                traceScheduler("cdn_recovery_wait")
                                Thread.sleep(25)
                                continue
                            }
                            break
                        }
                        val assignedUrl = reservation.url
                        monitor.trace(
                            "cdn_dispatch_selection",
                            "reader" to readerId,
                            "block" to id,
                            "track" to track.kind.name,
                            "host" to assignedUrl.toHttpUrlOrNull()?.host,
                            "selectionMs" to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - selectionBegan),
                        )
                        launch(assignedUrl, true, reservation)
                        monitor.state(id, DownloadBlockState.Active)
                    }
                    val completed = results.poll(100, TimeUnit.MILLISECONDS)
                    if (completed != null) {
                        active--
                        completed.first.finished = true
                        completed.first.releaseDuplicate()
                        val result = completed.second
                        val winner = result.getOrNull()
                        if (winner != null) {
                            ensureCurrent()
                            if (!completed.first.ordinary) {
                                monitor.routeRescueProvided(
                                    completed.first.url
                                        .toHttpUrlOrNull()
                                        ?.host
                                        .orEmpty(),
                                )
                            }
                            attempts.filter { it !== completed.first && it.measurementLease == null }.forEach {
                                it
                                    .cancel()
                            }
                            monitor.recordBytes(track.kind, start, winner.bytes)
                            monitor.rangeReady(track.kind, start, start + winner.bytes.size, readyEpoch)
                            monitor.progress(id, winner.bytes.size.toLong())
                            monitor.state(id, DownloadBlockState.Complete)
                            completed.first.retainResult()
                            return winner
                        }
                        completed.first.discardResult()
                        attempts.remove(completed.first)
                        hedged = false
                        lastError = asIo(requireNotNull(result.exceptionOrNull()))
                        if (lastError is UnsupportedRange) unsupported = lastError as UnsupportedRange
                        monitor.state(id, DownloadBlockState.Retrying)
                    }
                    displayedProgress =
                        maxOf(displayedProgress, attempts.maxOfOrNull { it.prefix.size + it.received } ?: 0)
                    monitor.progress(id, displayedProgress)
                    val primary = attempts.firstOrNull { !it.finished } ?: continue
                    val now = System.nanoTime()
                    val deadline = monitor.deadlineNanos(track.kind, start)
                    val age = now - primary.began
                    val blockingNow = isFirstBlockingRange()
                    val liveNow = attempts.filter { !it.finished }
                    val leading =
                        attempts
                            .filter { !it.finished && it.partialBody != null }
                            .maxByOrNull { it.prefix.size + it.received }
                    // received is published only after those bytes are written. Capture its
                    // value once; later progress by the source must never shift the join offset.
                    val leadingBody = leading?.partialBody
                    val prefixReceived = if (leadingBody != null) leading.received.toInt() else 0
                    val prefixSize = if (leadingBody != null) leading.prefix.size + prefixReceived else 0
                    val remainingRescueBytes = end - start + 1 - prefixSize
                    val waitForTail = RescueStitch.shouldWait(remainingRescueBytes)

                    fun snapshotPrefix(): RescuePrefix {
                        if (leading == null || leadingBody == null) return RescuePrefix()
                        return synchronized(leading) {
                            val source = leading.bodySource ?: return@synchronized RescuePrefix()
                            val bytes =
                                RescueStitch.prefix(
                                    leading.prefix,
                                    leadingBody,
                                    prefixReceived,
                                    (end - start + 1).toInt(),
                                )
                            val suffix = source.view(0, prefixReceived.toLong())
                            val provenance =
                                try {
                                    transferLedger.concat(listOfNotNull(leading.prefixProvenance, suffix))
                                } finally {
                                    suffix.close()
                                }
                            leading.accounting.preservePrefix(prefixReceived.toLong())
                            RescuePrefix(bytes, provenance)
                        }
                    }
                    val backupTime =
                        resolver
                            .candidates(track, remainingRescueBytes)
                            .filter { url ->
                                attempts.none { it.url.toHttpUrlOrNull()?.host == url.toHttpUrlOrNull()?.host }
                            }.mapNotNull { resolver.estimatedTransferNanos(it, remainingRescueBytes) }
                            .minOrNull()
                    val preemptive =
                        EmergencyRescuePolicy.shouldRampBeforeDeadline(
                            playing = monitor.isPlaybackAdvancing(),
                            blockingRange = blockingNow,
                            allAttemptsAtRisk =
                                deadline != null &&
                                    liveNow.all {
                                        val remaining =
                                            (
                                                minOf(
                                                    end - start + 1,
                                                    it.expectedBytes,
                                                ) - it.received
                                            ).coerceAtLeast(0)
                                        val forecast =
                                            if (it.received >
                                                0
                                            ) {
                                                remaining.toDouble() * (now - it.began) / it.received
                                            } else {
                                                null
                                            }
                                        now - it.lastProgressNanos >= TimeUnit.SECONDS.toNanos(2) ||
                                            (forecast != null && forecast >= deadline - now)
                                    },
                            activeAttempts = attempts.count { !it.finished },
                            youngestAttemptAgeNanos = liveNow.minOfOrNull { now - it.began } ?: 0,
                            timeSinceLastLaunchNanos =
                                if (lastRescueLaunch ==
                                    Long.MIN_VALUE
                                ) {
                                    age
                                } else {
                                    now - lastRescueLaunch
                                },
                            deadlineSlackNanos = deadline?.minus(now),
                            backupTransferNanos = backupTime,
                            maxRequests = limit,
                        )
                    val awaitingReadiness = monitor.isAwaitingReadiness()
                    val readinessOpportunity =
                        !awaitingReadiness ||
                            ReadinessRescuePolicy.observationComplete(age, primary.requiredOpportunityNanos)
                    val rescue =
                        blockingNow &&
                            readinessOpportunity &&
                            if (deadline != null) {
                                val backup = rescueUrl ?: candidates.firstOrNull { it != primary.url }
                                val takeover =
                                    backup?.let { resolver.estimatedTransferNanos(it, remainingRescueBytes) }
                                        ?: TimeUnit.SECONDS.toNanos(1)
                                if (primary.measurementLease != null) {
                                    // A protected experiment keeps its observation window until
                                    // the last safe takeover point, even during burst gaps.
                                    deadline - now <= takeover + explorationMarginNanos
                                } else {
                                    primary.atRisk(deadline, end - start + 1, now, takeover)
                                }
                            } else {
                                age >= TimeUnit.MILLISECONDS.toNanos(900)
                            }
                    if (monitor.traceEnabled && now - lastDecisionTrace >= TimeUnit.SECONDS.toNanos(1)) {
                        lastDecisionTrace = now
                        monitor.trace(
                            "range_decision",
                            "reader" to readerId,
                            "block" to id,
                            "track" to track.kind.name,
                            "start" to start,
                            "end" to end,
                            "activeAttempts" to active,
                            "triedHosts" to tried.size,
                            "blocking" to blockingNow,
                            "rescueFocus" to (rescueFocus.get() === rescueKey),
                            "deadlineSlackMs" to deadline?.let { TimeUnit.NANOSECONDS.toMillis(it - now) },
                            "backupEstimateMs" to backupTime?.let { TimeUnit.NANOSECONDS.toMillis(it) },
                            "remainingBytes" to remainingRescueBytes,
                            "waitingForSmallTail" to waitForTail,
                            "rescueDue" to rescue,
                            "preemptiveDue" to preemptive,
                            "alreadyHedged" to hedged,
                            "paused" to monitor.isPlaybackPaused(),
                            "rebuffering" to monitor.isRebuffering(),
                            "awaitingReadiness" to awaitingReadiness,
                            "readinessOpportunity" to readinessOpportunity,
                            "globalSlotsFree" to httpAdmission.freeSlots,
                            "rampAttemptCap" to EmergencyRescuePolicy.attemptLimit(limit),
                            "recoveryExplorations" to recoveryExplorations.get(),
                            "recoveryExplorationLimit" to recoveryExplorationLimit,
                        )
                    }
                    if (rescue &&
                        !monitor.isPlaybackPaused()
                    ) {
                        urgentRanges.add(urgencyKey)
                    } else {
                        urgentRanges.remove(urgencyKey)
                    }
                    // Low-buffer discovery must not depend on a far-away block: that requirement
                    // traps playback in the same slow measured pool. Race useful bytes on the
                    // blocking range, retaining the experiment for a full throughput measurement.
                    val recoveryPressure =
                        !waitForTail &&
                            blockingNow &&
                            !startupScheduling() &&
                            !monitor.isPlaybackPaused() &&
                            (rescue || preemptive || monitor.isRebuffering())
                    if (waitForTail ||
                        monitor.isPlaybackPaused() ||
                        !blockingNow ||
                        attempts.count { !it.finished } >= EmergencyRescuePolicy.attemptLimit(limit) ||
                        (!recoveryPressure && !rescue && !preemptive)
                    ) {
                        rescueEligible = false
                        rescueTicket?.let(httpAdmission::cancel)
                        rescueTicket = null
                        memoryBudget.releaseRecoveryClaim(rescueKey)
                    }
                    val discoveryObserved =
                        lastRescueLaunch == Long.MIN_VALUE ||
                            attempts.filter { !it.finished }.all { now - it.began >= TimeUnit.SECONDS.toNanos(2) }
                    val discoveryNeeded = active == 1 || preemptive || monitor.isRebuffering()
                    if (recoveryPressure &&
                        discoveryNeeded &&
                        discoveryObserved &&
                        age >= TimeUnit.MILLISECONDS.toNanos(900) &&
                        attempts.count { !it.finished } < EmergencyRescuePolicy.attemptLimit(limit) &&
                        claimRescueFocus() &&
                        reserveRecoveryExplorationSlot()
                    ) {
                        var transferred = false
                        try {
                            if (acquireRescue(end - start + 1, exploration = true)) {
                                val reservation =
                                    resolver.reserveRecoveryExploration(
                                        track,
                                        remainingRescueBytes,
                                        tried,
                                    )
                                if (reservation == null) {
                                    releaseRescue(end - start + 1)
                                } else {
                                    val lease =
                                        SplitLease(ownsSplitSlot = false) { recoveryExplorations.decrementAndGet() }
                                    transferred = true
                                    try {
                                        monitor.exploration(id, ExplorationState.Testing)
                                        monitor.rescue(id)
                                        monitor.trace(
                                            "recovery_exploration_dispatch",
                                            "reader" to readerId,
                                            "block" to id,
                                            "track" to track.kind.name,
                                            "host" to reservation.url.toHttpUrlOrNull()?.host,
                                            "reason" to "blocking_range_capacity_discovery",
                                            "activeBefore" to active,
                                            "start" to start,
                                            "end" to end,
                                            "deadlineSlackMs" to
                                                deadline?.let { TimeUnit.NANOSECONDS.toMillis(it - now) },
                                        )
                                        launch(reservation.url, false, reservation, lease, snapshotPrefix())
                                        if (deadline != null && rescue) {
                                            recordMiss(primary)
                                        } else {
                                            primary.routeAssignment?.let { resolver.rescued(it, penalize = false) }
                                        }
                                        emergencyUsed = true
                                        hedged = true
                                        lastRescueLaunch = now
                                    } finally {
                                        lease.release()
                                    }
                                }
                            }
                        } finally {
                            if (!transferred) recoveryExplorations.decrementAndGet()
                        }
                    }
                    if (!waitForTail &&
                        active == 1 &&
                        !hedged &&
                        tried.size < MAX_ATTEMPTS &&
                        !monitor.isPlaybackPaused() &&
                        (rescue || preemptive) &&
                        claimRescueFocus() &&
                        attempts.count { !it.finished } < EmergencyRescuePolicy.attemptLimit(limit) &&
                        acquireRescue(end - start + 1)
                    ) {
                        val reservation =
                            resolver.reserveRescue(
                                track,
                                remainingRescueBytes,
                                deadline,
                                tried,
                            )
                        val alternate = reservation?.url
                        if (alternate == null) {
                            releaseRescue(end - start + 1)
                        } else {
                            hedged = true
                            monitor.trace(
                                "rescue_dispatch",
                                "reader" to readerId,
                                "block" to id,
                                "track" to track.kind.name,
                                "start" to start,
                                "end" to end,
                                "host" to alternate.toHttpUrlOrNull()?.host,
                                "reason" to
                                    (
                                        if (preemptive) {
                                            "predeadline_ramp"
                                        } else if (deadline ==
                                            null
                                        ) {
                                            "startup_timeout"
                                        } else {
                                            "deadline_risk"
                                        }
                                    ),
                                "activeBefore" to active,
                                "deadlineSlackMs" to deadline?.let { TimeUnit.NANOSECONDS.toMillis(it - now) },
                            )
                            monitor.rescue(id)
                            if (BuildConfig.DEBUG) {
                                runCatching {
                                    val reason =
                                        if (deadline == null) {
                                            "startup"
                                        } else {
                                            "deadline"
                                        }
                                    Log.d(
                                        "BvRange",
                                        "${track.kind} hedge reason=$reason host=${alternate.toHttpUrlOrNull()?.host}",
                                    )
                                }
                            }
                            if (deadline != null && rescue) {
                                recordMiss(primary)
                            } else {
                                primary.routeAssignment?.let { resolver.rescued(it, penalize = false) }
                            }
                            lastRescueLaunch = now
                            launch(alternate, false, reservation, prefix = snapshotPrefix())
                        }
                    }
                    val live = attempts.filter { !it.finished }
                    val blocking = isFirstBlockingRange()
                    if (blocking && monitor.isRebuffering() && deadline != null && deadline <= now) {
                        live.forEach { recordMiss(it, emergency = true, buffering = true) }
                    }
                    val overdueReadiness =
                        awaitingReadiness &&
                            !monitor.isPlaybackPaused() &&
                            live.all {
                                ReadinessRescuePolicy.observationComplete(
                                    now - it.began,
                                    it.requiredOpportunityNanos,
                                )
                            }
                    val emergency =
                        EmergencyRescuePolicy.shouldLaunch(
                            maxRequests = limit,
                            actualRebuffering = monitor.isRebuffering() || overdueReadiness,
                            blockingRange = blocking,
                            activeAttempts = attempts.count { !it.finished },
                            rescueAlreadyStarted = lastRescueLaunch != Long.MIN_VALUE,
                            youngestAttemptAgeNanos = live.minOfOrNull { now - it.began } ?: 0,
                            timeSinceLastLaunchNanos =
                                if (lastRescueLaunch ==
                                    Long.MIN_VALUE
                                ) {
                                    0
                                } else {
                                    now - lastRescueLaunch
                                },
                        )
                    if (!waitForTail &&
                        (
                            emergency ||
                                (preemptive && active > 1 && now - lastRescueLaunch >= TimeUnit.SECONDS.toNanos(2))
                        ) &&
                        tried.size < maxOf(MAX_ATTEMPTS, limit / 2) &&
                        claimRescueFocus() &&
                        attempts.count { !it.finished } < EmergencyRescuePolicy.attemptLimit(limit) &&
                        acquireRescue(end - start + 1)
                    ) {
                        val reservation = resolver.reserveRescue(track, remainingRescueBytes, deadline, tried)
                        if (reservation == null) {
                            releaseRescue(end - start + 1)
                        } else {
                            live.forEach { attempt ->
                                if (deadline?.let {
                                        attempt.atRisk(
                                            it,
                                            end - start + 1,
                                            now,
                                            backupTime ?: 0,
                                        )
                                    } == true ||
                                    emergency
                                ) {
                                    recordMiss(attempt, emergency = emergency && !awaitingReadiness)
                                }
                            }
                            monitor.rescue(id)
                            monitor.trace(
                                "rescue_dispatch",
                                "reader" to readerId,
                                "block" to id,
                                "track" to track.kind.name,
                                "start" to start,
                                "end" to end,
                                "host" to reservation.url.toHttpUrlOrNull()?.host,
                                "reason" to (
                                    if (emergency) {
                                        if (awaitingReadiness) "readiness_ramp" else "buffering_ramp"
                                    } else {
                                        "predeadline_ramp"
                                    }
                                ),
                                "activeBefore" to active,
                                "deadlineSlackMs" to deadline?.let { TimeUnit.NANOSECONDS.toMillis(it - now) },
                            )
                            emergencyUsed = true
                            lastRescueLaunch = now
                            launch(reservation.url, false, reservation, prefix = snapshotPrefix())
                        }
                    }
                }
                unsupported?.let { throw it }
                monitor.state(id, DownloadBlockState.Failed)
                throw lastError
            } finally {
                rescueTicket?.let(httpAdmission::cancel)
                acquiredSlot?.close()
                memoryBudget.releaseRecoveryClaim(rescueKey)
                synchronized(results) {
                    acceptingResults = false
                    while (true) {
                        val discarded = results.poll() ?: break
                        discarded.first.discardResult()
                        discarded.first.releaseDuplicate()
                    }
                }
                attempts.forEach { it.discardResult() }
                if (audioPermit) startupAudioPermit.release()
                if (rescueFocus.compareAndSet(rescueKey, null)) {
                    monitor.trace(
                        "rescue_focus",
                        "reader" to readerId,
                        "block" to id,
                        "track" to track.kind.name,
                        "state" to "released",
                    )
                }
                urgentRanges.remove(urgencyKey)
                attempts.filter { it.measurementLease == null || it.measurementLease.cancelled.get() }.forEach {
                    it
                        .cancel()
                }
            }
        }

        private fun traceAttemptProgress(attempt: Attempt) {
            if (!monitor.traceEnabled) return
            val now = System.nanoTime()
            val previous = attempt.lastTraceNanos.get()
            if (now - previous < TimeUnit.SECONDS.toNanos(1) ||
                !attempt.lastTraceNanos.compareAndSet(previous, now)
            ) {
                return
            }
            monitor.trace(
                "attempt_progress",
                "reader" to readerId,
                "attempt" to attempt.traceId,
                "track" to track.kind.name,
                "host" to attempt.url.toHttpUrlOrNull()?.host,
                "receivedBytes" to attempt.received,
                "expectedBytes" to attempt.expectedBytes,
                "elapsedMs" to TimeUnit.NANOSECONDS.toMillis(now - attempt.began),
                "silentMs" to TimeUnit.NANOSECONDS.toMillis(now - attempt.lastProgressNanos),
            )
        }

        private fun fetchAttempt(
            start: Long,
            end: Long,
            id: Long,
            token: Long,
            attempt: Attempt,
        ): Block {
            attempt.range = start..end
            val url = attempt.url
            val requestSpec = spec
            val expectedTotal = total
            val queuedAt = attempt.began

            fun ensureAttempt() {
                checkOpen()
                if (token != generation ||
                    id in revokedBlocks ||
                    attempt.cancelled.get()
                ) {
                    throw InterruptedIOException("Cancelled media range")
                }
            }
            val began = System.nanoTime()
            liveTraceAttempts.add(attempt)
            val startDeadline = monitor.deadlineNanos(track.kind, start)
            attempt.rescuePenaltyEligible = attempt.requiredOpportunityNanos?.let { required ->
                startDeadline != null && startDeadline - began >= required
            } == true
            var call: Call? = null
            var status = 0
            var received = 0
            var headersMs = -1L
            var outcome = "cancelled"
            var phase = "request_setup"
            val transferSource = transferLedger.newSource()
            synchronized(attempt) { attempt.bodySource = transferSource }
            monitor.trace(
                "attempt_start",
                "reader" to readerId,
                "attempt" to attempt.traceId,
                "block" to id,
                "track" to track.kind.name,
                "start" to start,
                "end" to end,
                "host" to url.toHttpUrlOrNull()?.host,
                "ordinary" to attempt.ordinary,
                "startupProbe" to (attempt.startupTotal != null),
                "exploration" to (attempt.measurementLease != null),
                "prefixBytes" to attempt.prefix.size,
                "queueMs" to TimeUnit.NANOSECONDS.toMillis(began - queuedAt),
                "deadlineSlackMs" to startDeadline?.let { TimeUnit.NANOSECONDS.toMillis(it - began) },
                "estimatedTransferMs" to attempt.requiredOpportunityNanos?.let { TimeUnit.NANOSECONDS.toMillis(it) },
            )
            try {
                val request = Request.Builder().url(url)
                config.requestHeaders.forEach { (key, value) -> request.header(key, value) }
                requestSpec?.httpRequestHeaders?.forEach { (key, value) -> request.header(key, value) }
                request.header("Range", "bytes=$start-$end").header("Accept-Encoding", "identity")
                call = client.newCall(request.build())
                attempt.call = call
                val experimentBudget =
                    if (attempt.measurementLease != null) {
                        monitor.explorationBudgetNanos(track.kind, start, end)
                    } else {
                        null
                    }
                val timeoutNanos =
                    experimentBudget?.let { (it.toDouble() * 1.5).coerceAtMost(Long.MAX_VALUE.toDouble()).toLong() }
                        ?: TimeUnit.SECONDS.toNanos(15)
                call.timeout().timeout(timeoutNanos.coerceAtLeast(1), TimeUnit.NANOSECONDS)
                if (attempt.measurementLease != null) {
                    monitor.trace(
                        "experiment_timeout_budget",
                        "attempt" to attempt.traceId,
                        "block" to id,
                        "track" to track.kind.name,
                        "budgetMs" to experimentBudget?.let { TimeUnit.NANOSECONDS.toMillis(it) },
                        "timeoutMs" to TimeUnit.NANOSECONDS.toMillis(timeoutNanos),
                    )
                }
                calls.add(call)
                ensureAttempt()
                if (attempt.reportBlockState) monitor.state(id, DownloadBlockState.Active)
                monitor.requestStarted()
                monitor.routeStarted(call.request().url.host)
                monitor.workerStarted(attempt.traceId, call.request().url.host, track.kind, attempt.workKind)
                try {
                    phase = "response_headers"
                    call.execute().use { response ->
                        status = response.code
                        headersMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
                        if (response.code == 200) throw UnsupportedRange(url)
                        if (response.code == 416 && response.header("Content-Range") == "bytes */$start") {
                            if (expectedTotal != null &&
                                expectedTotal != start
                            ) {
                                throw IOException("Range total changed")
                            }
                            outcome = "complete"
                            monitor.discard(listOf(id))
                            return Block(id, start, ByteArray(0), start)
                        }
                        if (response.code != 206) throw IOException("Range HTTP ${response.code}")
                        if (response.header("Content-Encoding")?.let { !it.equals("identity", true) } == true) {
                            throw IOException("Encoded range response")
                        }
                        if (response.header("Content-Range")?.endsWith("/*") == true) throw UnsupportedRange(url)
                        phase = "range_validation"
                        val range = RangeResponse.parse(response.header("Content-Range"), start, end, expectedTotal)
                        val expected = (range.end - range.start + 1).toInt()
                        attempt.expectedBytes = expected.toLong()
                        val body = response.body ?: throw IOException("Empty range body")
                        if (body.contentLength() != -1L &&
                            body.contentLength() != expected.toLong()
                        ) {
                            throw IOException("Range length mismatch")
                        }
                        monitor.trace(
                            "attempt_headers",
                            "reader" to readerId,
                            "attempt" to attempt.traceId,
                            "block" to id,
                            "status" to status,
                            "headersMs" to headersMs,
                            "rangeStart" to range.start,
                            "rangeEnd" to range.end,
                            "totalBytes" to range.total,
                        )
                        phase = "body_read"
                        val bytes = ByteArray(expected)
                        attempt.partialBody = bytes
                        body.byteStream().use { input ->
                            while (received < expected) {
                                ensureAttempt()
                                val count = input.read(bytes, received, expected - received)
                                if (count < 0) throw IOException("Truncated media range")
                                received += count
                                synchronized(attempt) {
                                    transferSource.received(count.toLong())
                                    attempt.received = received.toLong()
                                }
                                monitor.routeProgress(call.request().url.host, count)
                                attempt.lastProgressNanos = System.nanoTime()
                                resolver.requestProgress(requireNotNull(attempt.routeAssignment), received.toLong())
                            }
                            if (input.read() != -1) {
                                received++
                                transferSource.received(1)
                                monitor.routeProgress(call.request().url.host, 1)
                                throw IOException("Overlong media range")
                            }
                        }
                        ensureAttempt()
                        phase = "completion_validation"
                        attempt.startupTotal?.let { shared ->
                            if (!shared.compareAndSet(-1, range.total) && shared.get() != range.total) {
                                throw IOException("Startup CDN total mismatch")
                            }
                        }
                        resolver.success(
                            url,
                            bytes.size,
                            System.nanoTime() - began,
                            mediaTimeMs = monitor.schedulingTimeMs(track.kind, start),
                            evidenceEpoch = speedEvidenceEpoch,
                            startupProbe = attempt.startupTotal != null,
                            onTime =
                                !requireNotNull(attempt.routeAssignment).rescued &&
                                    monitor.deadlineNanos(track.kind, start)?.let { System.nanoTime() <= it } == true,
                        )
                        outcome = "complete"
                        val parentStart = start - attempt.prefix.size
                        val delivered =
                            if (attempt.prefix.isEmpty()) {
                                bytes
                            } else {
                                RescueStitch.join(attempt.prefix, bytes, (end - parentStart + 1).toInt())
                            }
                        val bodyView = transferSource.view(0, received.toLong())
                        val provenance =
                            if (attempt.prefixProvenance == null) {
                                bodyView
                            } else {
                                try {
                                    transferLedger.concat(listOf(requireNotNull(attempt.prefixProvenance), bodyView))
                                } finally {
                                    bodyView.close()
                                }
                            }
                        attempt.publishProvenance(provenance)
                        return Block(id, parentStart, delivered, range.total, provenance = provenance)
                    }
                } catch (error: IOException) {
                    outcome =
                        when {
                            attempt.cancelled.get() || cancelled || closed || token != generation -> "cancelled"
                            error is UnsupportedRange -> "sequential"
                            status !in listOf(0, 200, 206) -> "HTTP$status"
                            else -> error.javaClass.simpleName
                        }
                    if (outcome !in listOf("cancelled", "sequential")) resolver.failure(url)
                    throw error
                } finally {
                    if (attempt.measurementLease != null) {
                        monitor.explorationFinished(id, outcome == "complete", outcome == "cancelled")
                    }
                    monitor.requestFinished()
                    monitor.routeFinished(
                        call.request().url.host,
                        received.toLong(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began),
                        failure = if (outcome in listOf("complete", "sequential", "cancelled")) null else outcome,
                        cooldownRemainingMs = resolver.cooldownRemainingMs(url),
                        cancelled = outcome == "cancelled",
                        confidence = resolver.schedulingConfidence(url),
                    )
                }
            } catch (unsupported: UnsupportedRange) {
                outcome = "sequential"
                throw unsupported
            } catch (error: IOException) {
                throw error
            } finally {
                monitor.trace(
                    "attempt_end",
                    "reader" to readerId,
                    "attempt" to attempt.traceId,
                    "block" to id,
                    "track" to track.kind.name,
                    "host" to url.toHttpUrlOrNull()?.host,
                    "status" to status,
                    "receivedBytes" to received,
                    "headersMs" to headersMs,
                    "elapsedMs" to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began),
                    "outcome" to outcome,
                    "phase" to phase,
                )
                if (BuildConfig.DEBUG) {
                    // Host/status only: signed paths, query tokens and header values are never logged.
                    val host =
                        call
                            ?.request()
                            ?.url
                            ?.host
                            .orEmpty()
                    val queueMs = TimeUnit.NANOSECONDS.toMillis(began - queuedAt)
                    val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - began)
                    try {
                        Log.d(
                            "BvRange",
                            "${track.kind} host=$host status=$status bytes=$received queueMs=$queueMs " +
                                "headersMs=$headersMs elapsedMs=$elapsedMs outcome=$outcome",
                        )
                    } catch (_: RuntimeException) {
                        // Diagnostics must never change a validated transport result.
                    }
                }
                val dispositionReason =
                    when {
                        outcome !in listOf("complete", "cancelled", "sequential") -> DownloadOverheadKind.Failure
                        attempt.workKind == DownloadWorkKind.Probe -> DownloadOverheadKind.Probe
                        attempt.workKind == DownloadWorkKind.Exploration -> DownloadOverheadKind.Exploration
                        attempt.workKind == DownloadWorkKind.Rescue -> DownloadOverheadKind.Rescue
                        outcome == "cancelled" -> DownloadOverheadKind.Cancelled
                        else -> DownloadOverheadKind.Other
                    }
                attempt.accounting.finish(received.toLong(), dispositionReason)
                synchronized(attempt) {
                    attempt.bodySource = null
                    attempt.partialBody = null
                    transferSource.finish(dispositionReason)
                    attempt.releasePrefix()
                }
                monitor.workerFinished(attempt.traceId)
                liveTraceAttempts.remove(attempt)
                finishDetached()
                call?.let { calls.remove(it) }
                attempt.transportLease?.close()
                attempt.payloadOwner?.let(::finishPayloadUser)
                if (!attempt.ordinary || attempt.startupTotal != null) {
                    val released = AtomicBoolean(false)
                    attempt.releaseDuplicate = {
                        if (released.compareAndSet(false, true)) {
                            attempt.prefix = ByteArray(0)
                            memoryBudget.releaseRescue(attempt.reservedBytes.takeIf { it > 0 } ?: (end - start + 1))
                        }
                    }
                }
                attempt.measurementLease?.release(attempt.cancelMeasurement)
                resolver.requestFinished(requireNotNull(attempt.routeAssignment))
            }
        }

        private fun startSequential(url: String): Long {
            admissionReady = false
            cancelWindow()
            block = null
            sequentialAudioPermit.set(acquireStartupAudio())
            try {
                sequentialLease = acquireOrdinary(position, generation, demanded = true)
            } catch (error: Exception) {
                if (sequentialAudioPermit.getAndSet(false)) startupAudioPermit.release()
                throw asIo(error)
            }
            sequentialPermit = true
            sequentialWorkerId =
                traceIds.incrementAndGet().also {
                    monitor.workerStarted(
                        it,
                        url.toHttpUrlOrNull()?.host.orEmpty(),
                        track.kind,
                        DownloadWorkKind.Ordinary,
                    )
                }
            monitor.requestStarted()
            sequentialBytes = 0
            sequentialCompleted = false
            sequentialFailure = null
            // Track the compatibility call too: DefaultDataSource cannot expose a pending
            // OkHttp open() call, which otherwise survives seeks until its network timeout.
            val token = generation
            val reader =
                if (config.enabled && spec?.httpMethod == DataSpec.HTTP_METHOD_GET) {
                    OkHttpDataSource
                        .Factory(
                            object : Call.Factory {
                                override fun newCall(request: Request): Call {
                                    val call = client.newCall(request)
                                    synchronized(this@RangeDataSource) {
                                        calls.add(call)
                                        if (cancelled || closed || generation != token) {
                                            call.cancel()
                                        } else {
                                            sequentialHost = request.url.host
                                            sequentialBegan = System.nanoTime()
                                            monitor.trace(
                                                "sequential_start",
                                                "reader" to readerId,
                                                "track" to track.kind.name,
                                                "host" to request.url.host,
                                                "start" to position,
                                            )
                                            monitor.routeStarted(request.url.host)
                                        }
                                    }
                                    return call
                                }
                            },
                        ).setDefaultRequestProperties(config.requestHeaders)
                        .createDataSource()
                } else {
                    fallback.createDataSource()
                }
            sequential = reader
            checkOpen()
            val original = requireNotNull(spec)
            val remaining =
                if (original.length == C.LENGTH_UNSET.toLong()) {
                    C.LENGTH_UNSET.toLong()
                } else {
                    original.length - (position - original.position)
                }
            return try {
                reader.open(
                    original
                        .buildUpon()
                        .setUri(url)
                        .setPosition(position)
                        .setLength(remaining)
                        .build(),
                )
            } catch (error: IOException) {
                sequentialFailure = if (cancelled || closed) null else error.javaClass.simpleName
                throw error
            }
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (length == 0) return 0
            lastReadNanos = System.nanoTime()
            checkReadOpen()
            sequential?.let { return readSequential(it, buffer, offset, length) }
            if (position >= endExclusive) return C.RESULT_END_OF_INPUT
            if (block == null || blockOffset >= requireNotNull(block).length) {
                block?.let {
                    ids.remove(it.id)
                    it.release()
                }
                block = null
                blockOffset = 0
                fillWindow()
                var available =
                    synchronized(pending) { pending.peek()?.takeIf { it.start == position }?.also { pending.poll() } }
                while (available == null) {
                    waitForPlanning()
                    fillWindow()
                    available =
                        synchronized(
                            pending,
                        ) { pending.peek()?.takeIf { it.start == position }?.also { pending.poll() } }
                }
                val next = available
                try {
                    block = awaitBlock(next, refill = true)
                } catch (error: Exception) {
                    val cause = if (error is ExecutionException) error.cause else error
                    if (cause is UnsupportedRange) {
                        startSequential(cause.url)
                        return readSequential(requireNotNull(sequential), buffer, offset, length)
                    }
                    throw asIo(cause ?: error)
                }
                fillWindow()
            }
            var refill = false
            val count =
                synchronized(pending) {
                    // Keep a cache lease or payload owner alive through the actual copy/handoff.
                    // close/seek may cancel transport concurrently, but cannot dispose this view first.
                    checkReadOpen()
                    val current = requireNotNull(block)
                    val copied =
                        minOf(
                            length,
                            current.length - blockOffset,
                            (endExclusive - position).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                        )
                    current.bytes.copyInto(
                        buffer,
                        offset,
                        current.offset + blockOffset,
                        current.offset + blockOffset + copied,
                    )
                    current.provenance?.delivered((current.offset + blockOffset).toLong(), copied.toLong())
                    monitor.consumedBytes(track.kind, position, copied, readinessEpoch)
                    cache.markDelivered(track, position, position + copied)
                    position += copied
                    cache.setReadPosition(track, position)
                    blockOffset += copied
                    if (blockOffset >= current.length) {
                        block = null
                        blockOffset = 0
                        ids.remove(current.id)
                        current.release()
                        refill = true
                    }
                    copied
                }
            bytesTransferred(count)
            if (refill) requestRefill()
            return count
        }

        private fun readSequential(
            reader: DataSource,
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            val count =
                try {
                    reader.read(buffer, offset, length)
                } catch (error: IOException) {
                    sequentialFailure = if (cancelled || closed) null else error.javaClass.simpleName
                    throw error
                }
            if (count == C.RESULT_END_OF_INPUT) sequentialCompleted = true
            if (count > 0) {
                transferLedger.deliveredDirect(count.toLong())
                sequentialHost?.let { monitor.routeProgress(it, count) }
                monitor.consumedBytes(track.kind, position, count, readinessEpoch)
                monitor.recordBytes(track.kind, position, buffer.copyOfRange(offset, offset + count))
                position += count
                monitor.rangeReady(track.kind, position - count, position, readinessEpoch)
                sequentialBytes += count
                bytesTransferred(count)
            }
            return count
        }

        override fun getUri(): Uri? = sequential?.uri ?: uri

        override fun getResponseHeaders(): Map<String, List<String>> = sequential?.responseHeaders ?: emptyMap()

        private fun checkOpen() {
            if (closed ||
                cancelled ||
                Thread.currentThread().isInterrupted
            ) {
                throw InterruptedIOException("Playback download closed")
            }
        }

        private fun checkReadOpen() {
            checkOpen()
            if (detached) throw InterruptedIOException("Playback reader replaced by seek")
        }

        private fun cancelWindow() {
            synchronized(pending) {
                pending.forEach {
                    it.future.cancel(true)
                    it.release()
                }
                pending.clear()
                awaitedPending?.let {
                    it.future.cancel(true)
                    it.release()
                }
                awaitedPending = null
                awaitedWork?.cancel(true)
                awaitedWork = null
                protectedPayloads.clear()
                consumedProtectedPayloads.clear()
                payloadReservations.keys.toList().forEach(::releasePayload)
                plannedRanges.clear()
                workKeys.values.forEach { plannedWork.remove(it) }
                workKeys.clear()
                completedBlocks.clear()
            }
            calls.forEach { it.cancel() }
            monitor.discard(ids.toList())
            ids.clear()
        }

        private fun detachForSeek(window: LongRange): Boolean {
            memoryBudget.releaseRecoveryClaim(memoryDemandKey)
            synchronized(pending) {
                val work = (pending.toList() + listOfNotNull(awaitedPending)).distinctBy { it.future }
                val useful = work.filter { !it.future.isDone && overlaps(it.start..(it.start + it.length - 1), window) }
                if (useful.isEmpty()) return false
                val cancelledCount = work.count { it !in useful && !it.future.isDone }
                detached = true
                // Publish ownership before cancelling anything: late successful completions can
                // move directly into the cache, while claimed work remains reserved for its reader.
                val handedOver =
                    useful.mapNotNull { item ->
                        // Keep the original transport owner through any number of seeks. Wrapping
                        // another reader's view would strand reservations when cache hits bypass it.
                        val retained = item.retained ?: RetainedRange(this, item).also { retainedOwned.add(it) }
                        retained.takeIf { it.offerAgain() }
                    }
                val retainedStarts = useful.map { it.start }.toSet()
                pending.clear()
                awaitedPending = null
                awaitedWork = null
                awaitedRange = null
                block?.release?.invoke()
                block = null
                // Retry cache admission after releasing this reader's cached playback lease.
                completedBlocks.values.toList().forEach {
                    cache.put(
                        track,
                        it.start,
                        it.bytes,
                        it.total,
                        abandoned = true,
                        provenance = it.provenance,
                    )
                }
                work.filter { it !in useful }.forEach {
                    it.future.cancel(true)
                    it.release()
                }
                plannedRanges.clear()
                workKeys.entries.filter { it.key !in retainedStarts }.forEach {
                    workKeys.remove(it.key, it.value)
                    plannedWork.remove(it.value)
                }
                payloadReservations.keys.filter { it !in retainedStarts }.forEach(::releasePayload)
                liveTraceAttempts
                    .filter { attempt ->
                        attempt.range?.let { overlaps(it, window) } != true
                    }.forEach { it.cancel() }
                val discard = ids.filter { id -> useful.none { it.id == id } }
                monitor.discard(discard)
                ids.removeAll(discard.toSet())
                // Completion may race the ownership handover; the result map closes that gap.
                handedOver.forEach { it.owner.rangeFinished(it.pending.start, it.pending.future) }
                monitor.trace(
                    "seek_reader_retained",
                    "reader" to readerId,
                    "track" to track.kind.name,
                    "kept" to useful.size,
                    "cancelled" to cancelledCount,
                    "windowStart" to window.first,
                    "windowEnd" to window.last,
                )
                finishDetached()
                return true
            }
        }

        @Synchronized
        override fun close() {
            // Media3 may close again after an interrupted open already detached useful work.
            if (detached && !closed) return
            monitor.trace(
                "reader_close",
                "reader" to readerId,
                "track" to track.kind.name,
                "positionBytes" to position,
                "generation" to generation,
            )
            val hint = if (!closed && !detached && sequential == null) seekWindows.remove(track.kind) else null
            if (hint != null && hint.expires >= System.nanoTime() && detachForSeek(hint.range)) {
                if (started) {
                    started = false
                    transferEnded()
                }
                uri = null
                return
            }
            cancelled = true
            memoryBudget.releaseRecoveryClaim(memoryDemandKey)
            generation++
            synchronized(pending) {
                block?.release?.invoke()
                completedBlocks.values.toList().forEach {
                    cache.put(track, it.start, it.bytes, it.total, abandoned = true, provenance = it.provenance)
                }
            }
            retainedOwned.toList().forEach { it.release(cancel = true) }
            cancelWindow()
            calls.clear()
            try {
                sequential?.close()
            } finally {
                sequential = null
                sequentialHost?.let { host ->
                    monitor.trace(
                        "sequential_end",
                        "reader" to readerId,
                        "track" to track.kind.name,
                        "host" to host,
                        "receivedBytes" to sequentialBytes,
                        "elapsedMs" to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sequentialBegan),
                        "outcome" to (sequentialFailure ?: if (sequentialCompleted) "complete" else "cancelled"),
                    )
                    monitor.routeFinished(
                        host,
                        sequentialBytes,
                        TimeUnit.NANOSECONDS.toMillis(
                            System.nanoTime() - sequentialBegan,
                        ),
                        sequentialFailure,
                        cancelled =
                            !sequentialCompleted && sequentialFailure == null,
                    )
                }
                sequentialHost = null
                if (sequentialAudioPermit.getAndSet(false)) startupAudioPermit.release()
                if (sequentialPermit) {
                    sequentialWorkerId?.let(monitor::workerFinished)
                    sequentialWorkerId = null
                    sequentialPermit = false
                    monitor.requestFinished()
                    sequentialLease?.close()
                    sequentialLease = null
                }
                block = null
                blockOffset = 0
                total = null
                uri = null
                readers.remove(this)
                if (started) {
                    started = false
                    transferEnded()
                }
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 4

        fun <T> concurrentSet(): MutableSet<T> = java.util.Collections.newSetFromMap(ConcurrentHashMap<T, Boolean>())

        fun asIo(error: Throwable): IOException {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            return when (error) {
                is IOException -> error
                is InterruptedException, is CancellationException ->
                    InterruptedIOException(
                        "Playback download interrupted",
                    )
                else -> IOException("Playback range failure", error)
            }
        }
    }
}
