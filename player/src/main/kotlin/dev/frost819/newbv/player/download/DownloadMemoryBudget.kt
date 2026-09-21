package dev.frost819.newbv.player.download

import java.util.IdentityHashMap

/**
 * Session-wide byte reservations, independent of the active HTTP request limit.
 *
 * Each reservation charges twice its range length to cover immutable prefix/suffix stitching
 * and split joins without allocating outside the allowance. Admission never waits while holding
 * another byte reservation.
 *
 * Ordinary ranges retain their reservation until consumed or discarded; cached arrays then
 * retain their actual byte charge under the same shared budget. Recovery has protected headroom and can borrow unused
 * ordinary capacity without exceeding the total budget. Pending claims prevent ordinary
 * refill from consuming bytes being reclaimed for an immediate playback need. Heap fractions leave
 * space for Media3, decoding and UI.
 */
internal class DownloadMemoryBudget(
    maxRequests: Int,
    minimumBlockBytes: Long,
    private val maxHeapBytes: Long = Runtime.getRuntime().maxMemory(),
) {
    init {
        require(maxRequests > 0)
        require(minimumBlockBytes >= 0)
        require(maxHeapBytes > 0)
    }

    private val playbackBudget = PlaybackBufferBudget(maxHeapBytes)

    private val threads = maxRequests.coerceAtMost(64)
    private val effectiveBlockBytes = minimumBlockBytes.coerceAtLeast(512 * 1024L)

    /** Ordinary planning partition; actual admission uses the shared retained/in-flight budget. */
    val payloadLimitBytes =
        minOf(
            maxOf(16 * MIB, threads * effectiveBlockBytes.coerceAtMost(128 * MIB) * 8),
            playbackBudget.payloadBytes,
        )

    /** Independent duplicate allowance, sized for at least one planning span when heap permits. */
    private val initialRescueLimitBytes =
        minOf(
            maxOf(
                minOf(16 * MIB, payloadLimitBytes),
                (threads / 2).coerceAtLeast(1) * effectiveBlockBytes.coerceAtMost(64 * MIB) * 2,
            ),
            playbackBudget.rescueBytes,
        )

    /** A demanded range plus speculative data must fit together, including copy headroom. */
    val planningSpanLimitBytes = minOf(8 * MIB, payloadLimitBytes / 4, initialRescueLimitBytes / 2)

    /** Absolute downloader ceiling; Media3 plus downloader remain at most half the VM heap. */
    val maximumBytes = (maxHeapBytes / 2 - playbackBudget.media3Bytes).coerceAtLeast(0)
    private val initialBudgetBytes = payloadLimitBytes + playbackBudget.cacheBytes + initialRescueLimitBytes
    private var budgetBytes = initialBudgetBytes
    private var allowedBytes = initialBudgetBytes
    private val blockShape = mutableMapOf<DownloadTrack, Long>()

    /** Duplicate capacity follows the largest current block, bounded to preserve ordinary work. */
    val rescueLimitBytes: Long
        @Synchronized get() = rescueTarget()

    /** Ordinary/cache admission excludes rescue capacity, including rescues already in flight. */
    val sharedLimitBytes: Long
        @Synchronized get() = (budgetBytes - protectedRecoveryBytes()).coerceAtLeast(0)

    /** Seeds unknown tracks conservatively until their actual partitions are observed. */
    @Synchronized
    fun configureTracks(tracks: Set<DownloadTrack>) {
        tracks.forEach { blockShape.getOrPut(it) { minOf(planningSpanLimitBytes, maxOf(effectiveBlockBytes, MIB)) } }
    }

    /** Latest planned partition per track, updated even when admission must wait. */
    @Synchronized
    fun observeBlock(
        track: DownloadTrack,
        bytes: Long,
    ) {
        require(bytes > 0)
        blockShape[track] = bytes
    }

    /** Unused ordinary-demand protection; already charged head blocks are credited once. */
    @Synchronized
    fun demandReserve(headCharges: Map<DownloadTrack, Long>): Long =
        blockShape.entries.sumOf { (track, bytes) -> (bytes * 2 - (headCharges[track] ?: 0L)).coerceAtLeast(0) }

    private fun rescueTarget(): Long =
        if (blockShape.isEmpty()) {
            minOf(initialRescueLimitBytes, budgetBytes)
        } else {
            minOf(
                2 * requireNotNull(blockShape.values.maxOrNull()) * (threads / 2).coerceAtLeast(1),
                playbackBudget.rescueBytes,
                budgetBytes / 3,
            )
        }

    /**
     * Applies pressure immediately; growth remains demand-driven. Known resident arrays are
     * credited once because they are already included in measured heap/device usage. Excluding
     * unregistered in-flight arrays is conservative. No unread array is evicted by this method.
     */
    @Synchronized
    fun refreshMemory(sample: DownloadMemorySnapshot?) {
        if (sample == null) {
            allowedBytes = minOf(allowedBytes, initialBudgetBytes)
        } else {
            val resident = arrays.keys.sumOf { it.size.toLong() }
            val media3Remaining = (playbackBudget.media3Bytes - sample.media3Bytes).coerceAtLeast(0)
            val heapRoom =
                (maxHeapBytes - sample.heapUsedBytes - maxHeapBytes / 4 - media3Remaining).coerceAtLeast(0)
            val systemReserve = maxOf(sample.deviceThresholdBytes * 2, sample.deviceTotalBytes / 10)
            val deviceRoom =
                if (sample.deviceLowMemory) {
                    0L
                } else {
                    (sample.deviceAvailableBytes - systemReserve).coerceAtLeast(0) / 4
                }
            allowedBytes = minOf(maximumBytes, resident + heapRoom, resident + deviceRoom)
        }
        budgetBytes = minOf(budgetBytes, allowedBytes)
    }

    private fun growFor(requiredBytes: Long) {
        if (requiredBytes > budgetBytes && allowedBytes > budgetBytes) {
            budgetBytes = minOf(allowedBytes, maxOf(requiredBytes, budgetBytes + 8 * MIB))
        }
    }

    /** Current capacity and shape-derived reserves for admission diagnostics. */
    @Synchronized
    fun capacityFields(): Map<String, Any?> =
        mapOf(
            "budgetBytes" to budgetBytes,
            "allowedBytes" to allowedBytes,
            "maximumBytes" to maximumBytes,
            "rescueReserveBytes" to rescueTarget(),
            "videoBlockBytes" to blockShape[DownloadTrack.Video],
            "audioBlockBytes" to blockShape[DownloadTrack.Audio],
            "copyFactor" to 2,
            "pendingRecoveryBytes" to recoveryClaims.values.sum(),
            "recoveryPayloadBytes" to recoveryPayloads.values.sum(),
        )

    private class ArrayOwners {
        var payload = 0
        var cached = 0
    }

    /** Consistent physical-array ownership and copy-inclusive reservation accounting. */
    data class Usage(
        val payloadReservedBytes: Long,
        val rescueReservedBytes: Long,
        val cachedOnlyBytes: Long,
        val cachedArrayBytes: Long,
        val sharedUsedBytes: Long,
        val sharedLimitBytes: Long,
    )

    private val arrays = IdentityHashMap<ByteArray, ArrayOwners>()
    private var cachedOnlyBytes = 0L
    private var cachedArrayBytes = 0L
    private var payloadReserved = 0L
    private var rescueReserved = 0L
    private val recoveryClaims = mutableMapOf<Any, Long>()
    private val recoveryPayloads = mutableMapOf<Any, Long>()

    private fun protectedRecoveryBytes(): Long =
        maxOf(
            (rescueTarget() - recoveryPayloads.values.sum()).coerceAtLeast(0),
            rescueReserved + recoveryClaims.values.sum(),
        )

    /** Protects copy-inclusive capacity while the caller reclaims memory; repeated calls replace the same claim. */
    @Synchronized
    fun claimRecovery(
        key: Any,
        bytes: Long,
    ) {
        require(bytes >= 0 && bytes <= Long.MAX_VALUE / 2)
        recoveryClaims[key] = bytes * 2
    }

    /** Cancels an unconverted claim; this never releases a running request's reservation. */
    @Synchronized
    fun releaseRecoveryClaim(key: Any) {
        recoveryClaims.remove(key)
    }

    /** Actual occupied bytes to release; pending claims fence ordinary refill, not other recovery requests. */
    @Suppress("UNUSED_PARAMETER")
    @Synchronized
    fun recoveryShortfall(
        bytes: Long,
        recoveryKey: Any? = null,
    ): Long {
        require(bytes >= 0 && bytes <= Long.MAX_VALUE / 2)
        // The HTTP admission controller picks the winner. Subtracting other pending claims here
        // would deadlock two eligible recoveries even when either could fit by itself.
        val room = maxOf(budgetBytes, allowedBytes) - payloadReserved - cachedOnlyBytes - rescueReserved
        return (bytes * 2 - room).coerceAtLeast(0)
    }

    /** Checks recovery eligibility without allocating, growing the budget, or consuming its claim. */
    @Synchronized
    fun canReserveRescue(
        bytes: Long,
        recoveryKey: Any? = null,
    ): Boolean = recoveryShortfall(bytes, recoveryKey) == 0L

    private fun tryRecoveryCharge(bytes: Long): Boolean {
        require(bytes >= 0 && bytes <= Long.MAX_VALUE / 2)
        val used = payloadReserved + cachedOnlyBytes + rescueReserved
        growFor(used + bytes * 2)
        return bytes * 2 <= budgetBytes - used
    }

    /**
     * Admits an otherwise blocked original through recovery capacity, charged exactly once as payload.
     * Its key must accompany [releasePayload] until consumption/discard; body/cache ownership is unchanged.
     */
    @Synchronized
    fun tryReserveRecoveryPayload(
        key: Any,
        bytes: Long,
    ): Boolean {
        require(key !in recoveryPayloads) { "Recovery payload already reserved" }
        if (!tryRecoveryCharge(bytes)) return false
        recoveryClaims.remove(key)
        recoveryPayloads[key] = bytes * 2
        payloadReserved += bytes * 2
        return true
    }

    /** Consistent byte usage for diagnostic snapshots; no reservation changes. */
    @Synchronized
    fun usage(): Pair<Long, Long> = payloadReserved to rescueReserved

    /** Shared usage counts arrays owned by both cache and an ordinary reservation only once. */
    @Synchronized
    fun snapshot(): Usage =
        Usage(
            payloadReserved,
            rescueReserved,
            cachedOnlyBytes,
            cachedArrayBytes,
            payloadReserved + cachedOnlyBytes,
            sharedLimitBytes,
        )

    /** Atomically reserve an ordinary range before it can allocate a body. */
    @Synchronized
    fun tryReservePayload(
        bytes: Long,
        demandHeadroom: Long = 0,
    ): Boolean {
        require(bytes >= 0 && demandHeadroom >= 0)
        growFor(payloadReserved + cachedOnlyBytes + bytes * 2 + demandHeadroom + protectedRecoveryBytes())
        val room = (sharedLimitBytes - payloadReserved - cachedOnlyBytes).coerceAtLeast(0)
        if (demandHeadroom > room || bytes > (room - demandHeadroom) / 2) return false
        payloadReserved += bytes * 2
        return true
    }

    /** Associates a validated immutable body with its still-live ordinary byte reservation. */
    @Synchronized
    fun attachPayload(array: ByteArray) {
        require(array.size.toLong() <= payloadReserved / 2)
        val owners = arrays.getOrPut(array) { ArrayOwners() }
        if (owners.payload == 0 && owners.cached > 0) cachedOnlyBytes -= array.size
        owners.payload++
    }

    /**
     * Admits an immutable cache backing array under the shared budget. Repeated identity
     * ownership costs no extra bytes; a live ordinary reservation already pays for that array.
     */
    @Synchronized
    fun retainCached(array: ByteArray): Boolean {
        val owners = arrays[array]
        val extra = if (owners == null || (owners.payload == 0 && owners.cached == 0)) array.size.toLong() else 0L
        if (extra > 0 && extra > sharedLimitBytes - payloadReserved - cachedOnlyBytes) return false
        val retained = owners ?: ArrayOwners().also { arrays[array] = it }
        if (retained.cached == 0) {
            cachedArrayBytes += array.size
            if (retained.payload == 0) cachedOnlyBytes += array.size
        }
        retained.cached++
        return true
    }

    /** Releases one cache owner, including a pinned cache entry only after its last lease closes. */
    @Synchronized
    fun releaseCached(array: ByteArray) {
        val owners = requireNotNull(arrays[array]) { "Unknown cached array" }
        require(owners.cached > 0)
        owners.cached--
        if (owners.cached == 0) {
            cachedArrayBytes -= array.size
            if (owners.payload == 0) {
                cachedOnlyBytes -= array.size
                arrays.remove(array)
            }
        }
    }

    /**
     * Releases an ordinary reservation and its optional body owner. When the cache still owns
     * that body, its actual array bytes remain charged instead of becoming free capacity.
     */
    @Synchronized
    fun releasePayload(
        bytes: Long,
        array: ByteArray? = null,
        recoveryKey: Any? = null,
    ) {
        require(bytes >= 0 && bytes <= payloadReserved / 2)
        if (recoveryKey != null) {
            require(recoveryPayloads[recoveryKey] == bytes * 2) { "Recovery payload reservation mismatch" }
        } else {
            require(
                bytes * 2 <= payloadReserved - recoveryPayloads.values.sum(),
            ) { "Recovery payload requires its key" }
        }
        val owners = array?.let { requireNotNull(arrays[it]) { "Unknown payload array" } }
        if (owners != null) {
            val body = requireNotNull(array)
            require(owners.payload > 0 && body.size <= bytes)
            owners.payload--
            if (owners.payload == 0) {
                if (owners.cached > 0) {
                    cachedOnlyBytes += body.size
                } else {
                    arrays.remove(body)
                }
            }
        }
        payloadReserved -= bytes * 2
        if (recoveryKey != null) recoveryPayloads.remove(recoveryKey)
    }

    /** Converts a pending claim into duplicate capacity, borrowing free ordinary bytes when necessary. */
    @Synchronized
    fun tryReserveRescue(
        bytes: Long,
        recoveryKey: Any? = null,
    ): Boolean {
        if (!tryRecoveryCharge(bytes)) return false
        if (recoveryKey != null) recoveryClaims.remove(recoveryKey)
        rescueReserved += bytes * 2
        return true
    }

    /** Release duplicate capacity when its request body is discarded or transferred. */
    @Synchronized
    fun releaseRescue(bytes: Long) {
        require(bytes >= 0 && bytes <= rescueReserved / 2)
        rescueReserved -= bytes * 2
    }

    private companion object {
        const val MIB = 1024 * 1024L
    }
}
