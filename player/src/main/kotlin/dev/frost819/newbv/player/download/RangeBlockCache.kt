package dev.frost819.newbv.player.download

import java.util.Collections
import java.util.IdentityHashMap

/**
 * Session-local storage for validated media ranges, bounded by backing-array bytes.
 * Normal reclamation requires delivered or abandoned ranges; explicit recovery eviction may remove unread ranges.
 * Reader anchors order normal victims; emergency callers select farthest future ranges by media time.
 * Arrays supplied to [put] must remain immutable. Playback leases pin their entire backing entry.
 * [reserve] and [release] account cache ownership by backing-array identity under the cache lock;
 * neither callback may acquire reader locks or call back into the cache.
 */
internal class RangeBlockCache(
    private val maxBytes: Long,
    private val reserve: (ByteArray) -> Boolean = { true },
    private val release: (ByteArray) -> Unit = {},
) : AutoCloseable {
    init {
        require(maxBytes >= 0)
    }

    private data class Key(
        val track: MediaTrackSource,
        val start: Long,
    )

    private class Entry(
        val key: Key,
        val bytes: ByteArray,
        val total: Long,
        var access: Long,
    ) {
        val endExclusive: Long = key.start + bytes.size
        var pins = 0
        var abandoned = false
        var acquiredSinceAbandon = false
        var provenance: ExactTransferLedger.Handle? = null
    }

    /** Metadata for a reusable cached interval, with an exclusive byte end and no payload reference. */
    data class Range(
        val track: MediaTrackSource,
        val start: Long,
        val endExclusive: Long,
    )

    /** Emergency eviction metadata; pin state is a snapshot and must be rechecked on removal. */
    data class EvictionCandidate(
        val track: MediaTrackSource,
        val start: Long,
        val endExclusive: Long,
        val pinned: Boolean,
    )

    /** Identity-deduplicated retained storage; pinned bytes are a subset of protected bytes. */
    data class RetentionStats(
        val protectedBytes: Long,
        val evictableBytes: Long,
        val pinnedBytes: Long,
    )

    private class RetentionFlags(
        var protectedFromEviction: Boolean = false,
        var pinned: Boolean = false,
    )

    /** A metadata-only slice; [bytes] stays valid until this lease is closed. */
    class Lease internal constructor(
        val bytes: ByteArray,
        val blockStart: Long,
        val start: Long,
        val endExclusive: Long,
        val total: Long,
        /** Borrowed provenance for the backing array; valid until this cache lease closes. */
        val provenance: ExactTransferLedger.Handle?,
        private val release: () -> Unit,
    ) : AutoCloseable {
        /** Index of the first requested byte in the immutable backing array. */
        val offset: Int = (start - blockStart).toInt()

        private var released = false

        @Synchronized
        override fun close() {
            if (released) return
            released = true
            release()
        }
    }

    private val entries = linkedMapOf<Key, Entry>()
    private val readPositions = mutableMapOf<MediaTrackSource, Long>()
    private val delivered = mutableMapOf<MediaTrackSource, DownloadByteLedger>()
    private var bytesUsed = 0L
    private var sequence = 0L
    private var closed = false

    /** Retained array bytes, including pinned arrays after cache closure until their leases close. */
    val usedBytes: Long get() = synchronized(this) { bytesUsed }

    /** Number of retained entries, including pinned entries awaiting release after closure. */
    val entryCount: Int get() = synchronized(this) { entries.size }

    /**
     * Updates the next byte required by a representation, including backward seeks.
     * Changes retention preference without deleting data or changing lease pins.
     * Negative positions and updates after closure are ignored.
     */
    @Synchronized
    fun setReadPosition(
        track: MediaTrackSource,
        position: Long,
    ) {
        if (closed || position < 0) return
        readPositions[track.copy(urls = track.urls.toList())] = position
    }

    /** Records only bytes successfully handed to Media3; seeks alone never imply delivery. */
    @Synchronized
    fun markDelivered(
        track: MediaTrackSource,
        start: Long,
        endExclusive: Long,
    ) {
        if (closed || start < 0 || endExclusive <= start) return
        val key = track.copy(urls = track.urls.toList())
        delivered.getOrPut(key) { DownloadByteLedger() }.add(start, endExclusive - 1)
    }

    /**
     * Makes pre-seek entries reclaimable without eagerly deleting them. Pins remain protected;
     * acquiring an entry for the new reader removes its abandonment status.
     */
    @Synchronized
    fun abandonUnread() {
        if (closed) return
        entries.values.forEach {
            it.abandoned = true
            it.acquiredSinceAbandon = false
        }
    }

    /**
     * Reclaims up to the requested backing-array bytes, rounded to whole eligible entries.
     * Returns cache references released, not physical heap savings when another owner remains.
     * Budget callbacks execute under this cache lock and must not call back into readers or cache.
     */
    @Synchronized
    fun reclaimEligible(bytesNeeded: Long = Long.MAX_VALUE): Long {
        if (closed || bytesNeeded <= 0) return 0
        var reclaimed = 0L
        for (entry in entries.values.filter { reclaimable(it) }.sortedWith(retentionOrder())) {
            if (reclaimed >= bytesNeeded) break
            reclaimed += entry.bytes.size
            remove(entry)
        }
        return reclaimed
    }

    /**
     * Snapshots whole entries for recovery selection without retaining payload references or changing LRU.
     * Callers protect demanded ranges and rank candidates by media time across representations.
     */
    @Synchronized
    fun evictionCandidates(): List<EvictionCandidate> {
        if (closed) return emptyList()
        return Collections.unmodifiableList(
            entries.values.map { entry ->
                EvictionCandidate(
                    track =
                        entry.key.track.copy(
                            urls =
                                Collections.unmodifiableList(
                                    entry.key.track.urls
                                        .toList(),
                                ),
                        ),
                    start = entry.key.start,
                    endExclusive = entry.endExclusive,
                    pinned = entry.pins > 0,
                )
            },
        )
    }

    /**
     * Explicit recovery-only eviction, including unread data, within an exclusive byte interval.
     * Removes only fully contained unpinned entries; partial overlaps and active leases remain intact.
     * Returns released cache-reference bytes, not physical savings if another owner retains an array.
     * Callers must first invalidate other owners and prevent obsolete completions from reinserting data.
     */
    @Synchronized
    fun evictUnread(
        track: MediaTrackSource,
        start: Long,
        endExclusive: Long,
    ): Long {
        if (closed || start < 0 || endExclusive <= start) return 0
        val victims =
            entries.values.filter {
                it.key.track == track && it.pins == 0 && it.key.start >= start && it.endExclusive <= endExclusive
            }
        val released = victims.sumOf { it.bytes.size.toLong() }
        victims.forEach(::remove)
        return released
    }

    /** Samples retention counters without exposing arrays or changing leases or eviction order. */
    @Synchronized
    fun retentionStats(): RetentionStats {
        val arrays = IdentityHashMap<ByteArray, RetentionFlags>()
        entries.values.forEach { entry ->
            val flags = arrays.getOrPut(entry.bytes) { RetentionFlags() }
            flags.protectedFromEviction = flags.protectedFromEviction || !reclaimable(entry)
            flags.pinned = flags.pinned || entry.pins > 0
        }
        return RetentionStats(
            protectedBytes = arrays.entries.filter { it.value.protectedFromEviction }.sumOf { it.key.size.toLong() },
            evictableBytes = arrays.entries.filterNot { it.value.protectedFromEviction }.sumOf { it.key.size.toLong() },
            pinnedBytes = arrays.entries.filter { it.value.pinned }.sumOf { it.key.size.toLong() },
        )
    }

    /** Reference-only sample for identity-based storage accounting; does not pin or update LRU order. */
    @Synchronized
    fun sampleStorage(): List<Pair<DownloadTrack, ByteArray>> = entries.values.map { it.key.track.kind to it.bytes }

    /**
     * Snapshots reusable entries without pinning them or changing eviction order.
     * Includes pinned entries while open; a closed cache exposes no reusable ranges.
     */
    @Synchronized
    fun ranges(): List<Range> {
        if (closed) return emptyList()
        return Collections.unmodifiableList(
            entries.values.map { entry ->
                Range(
                    track =
                        entry.key.track.copy(
                            urls =
                                Collections.unmodifiableList(
                                    entry.key.track.urls
                                        .toList(),
                                ),
                        ),
                    start = entry.key.start,
                    endExclusive = entry.endExclusive,
                )
            },
        )
    }

    /**
     * Retains a validated immutable range without copying. Returns false for invalid bounds,
     * incompatible totals for this representation, or insufficient reclaimable unpinned capacity.
     * Exact-start replacement never invalidates a pinned entry; the replacement is rejected instead.
     * [abandoned] marks obsolete completions reclaimable, including late completion racing close.
     * A repeat insertion cannot change protection restored by a new reader's acquisition.
     * [provenance] is borrowed on entry and retained only after successful admission.
     */
    @Synchronized
    fun put(
        track: MediaTrackSource,
        start: Long,
        bytes: ByteArray,
        total: Long,
        abandoned: Boolean = false,
        provenance: ExactTransferLedger.Handle? = null,
    ): Boolean {
        if (closed ||
            start < 0 ||
            total <= 0 ||
            start >= total ||
            bytes.isEmpty() ||
            bytes.size.toLong() > total - start ||
            bytes.size.toLong() > maxBytes
        ) {
            return false
        }
        if (entries.values.any { it.key.track == track && it.total != total }) return false
        val key = Key(track.copy(urls = track.urls.toList()), start)
        val previous = entries[key]
        if (previous?.bytes === bytes) {
            if (abandoned && !previous.acquiredSinceAbandon) previous.abandoned = true
            return true
        }
        if (previous?.pins?.let { it > 0 } == true) return false
        if (previous != null && !reclaimable(previous) && bytes.size < previous.bytes.size) return false
        val candidate =
            Entry(key, bytes, total, sequence).also {
                it.acquiredSinceAbandon = previous?.acquiredSinceAbandon == true
                it.abandoned = abandoned && !it.acquiredSinceAbandon
            }
        val needed = bytes.size.toLong() - (previous?.bytes?.size ?: 0)
        val removable =
            entries.values
                .filter { it !== previous && reclaimable(it) }
                .sortedWith(retentionOrder())
        val available = maxBytes - bytesUsed + (previous?.bytes?.size ?: 0) + removable.sumOf { it.bytes.size.toLong() }
        // Failed admissions leave existing entries untouched, including their recency.
        if (bytes.size > available) return false
        // Reserve before mutation so failed shared-budget admission leaves existing data intact.
        // The session can reclaim eligible entries before reserving the next network request.
        if (!reserve(bytes)) return false
        candidate.provenance = provenance?.slice(0, bytes.size.toLong())
        var reclaim = (needed - (maxBytes - bytesUsed)).coerceAtLeast(0)
        for (entry in removable) {
            if (reclaim == 0L) break
            remove(entry)
            reclaim = (reclaim - entry.bytes.size).coerceAtLeast(0)
        }
        if (previous != null) remove(previous)
        entries[key] = candidate
        sequence++
        bytesUsed += bytes.size
        return true
    }

    /**
     * Pins the entry covering [position] with the farthest useful end, clipped to [maxEndExclusive].
     * Returns null for a gap, an empty requested range, or a closed cache. No payload is copied.
     */
    @Synchronized
    fun acquire(
        track: MediaTrackSource,
        position: Long,
        maxEndExclusive: Long,
    ): Lease? {
        if (closed || position < 0 || maxEndExclusive <= position) return null
        val entry =
            entries.values
                .filter {
                    it.key.track == track && position >= it.key.start && position < it.endExclusive
                }.maxWithOrNull(
                    compareBy<Entry> { minOf(it.endExclusive, maxEndExclusive) }.thenBy { it.access },
                ) ?: return null
        entry.abandoned = false
        entry.acquiredSinceAbandon = true
        entry.pins++
        entry.access = sequence++
        return Lease(
            entry.bytes,
            entry.key.start,
            position,
            minOf(entry.endExclusive, maxEndExclusive),
            entry.total,
            entry.provenance,
        ) {
            release(entry)
        }
    }

    /** Earliest cached block start strictly after [position], for bounding a network gap. */
    @Synchronized
    fun nextStart(
        track: MediaTrackSource,
        position: Long,
    ): Long? {
        if (closed || position < 0) return null
        return entries.keys.filter { it.track == track && it.start > position }.minOfOrNull { it.start }
    }

    @Synchronized
    private fun release(entry: Entry) {
        if (entry.pins <= 0) return
        entry.pins--
        if (closed && entry.pins == 0) remove(entry)
    }

    private fun remove(entry: Entry) {
        if (entries[entry.key] === entry) {
            entries.remove(entry.key)
            bytesUsed -= entry.bytes.size
            release(entry.bytes)
            entry.provenance?.close()
            entry.provenance = null
        }
    }

    private fun reclaimable(entry: Entry): Boolean =
        entry.pins == 0 &&
            (
                entry.abandoned ||
                    delivered[entry.key.track]?.unconsumedBytes(listOf(entry.key.start until entry.endExclusive)) == 0L
            )

    private fun retentionOrder(): Comparator<Entry> =
        compareBy<Entry> { usefulness(it) }.thenByDescending { distance(it) }.thenBy { it.access }

    // Anchors only order entries already eligible for eviction; they never establish delivery.
    private fun usefulness(entry: Entry): Int {
        val position = readPositions[entry.key.track] ?: return 1
        return if (entry.endExclusive <= position) 0 else 2
    }

    private fun distance(entry: Entry): Double {
        val position = readPositions[entry.key.track] ?: return 0.0
        val bytes =
            if (entry.endExclusive <= position) {
                position - entry.endExclusive
            } else {
                (entry.key.start - position).coerceAtLeast(0)
            }
        // Byte distance alone would favor tiny audio files over equally near video. File fraction
        // approximates media distance until segment timing is available here.
        return bytes.toDouble() / entry.total
    }

    /** Stops admission and lookup; pinned bytes remain retained until their last lease is released. */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        readPositions.clear()
        delivered.clear()
        entries.values.filter { it.pins == 0 }.forEach(::remove)
    }
}
