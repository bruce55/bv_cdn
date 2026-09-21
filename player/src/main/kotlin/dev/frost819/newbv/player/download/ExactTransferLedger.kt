package dev.frost819.newbv.player.download

/** Tracks ownership of received body-byte copies without retaining payloads or influencing admission. */
internal class ExactTransferLedger {
    private val lock = Any()
    private val sources = mutableSetOf<Source>()
    private var downloaded = 0L
    private var used = 0L
    private var discarded = 0L
    private val discardedKinds = mutableMapOf<DownloadOverheadKind, Long>()

    /** A consistent partition of all observed body bytes, plus live metadata diagnostics. */
    data class Snapshot(
        val downloadedBytes: Long,
        val usedBytes: Long,
        val pendingBytes: Long,
        val discardedBytes: Long,
        val discardedByKind: Map<DownloadOverheadKind, Long>,
        val liveSourceCount: Int,
        val liveRangeCount: Int,
    ) {
        /** Ground-truth payload partition; excludes wire overhead and Media3 buffer disposal. */
        fun fields(): Map<String, Any?> =
            mapOf(
                "downloadedBytes" to downloadedBytes,
                "usedBytes" to usedBytes,
                "pendingBytes" to pendingBytes,
                "discardedBytes" to discardedBytes,
                "discardedByKind" to discardedByKind.mapKeys { it.key.name },
                "liveSources" to liveSourceCount,
                "liveRanges" to liveRangeCount,
                "scope" to "received_body_copies;used_on_first_delivery;excludes_media3_disposal",
            )
    }

    internal data class Range(
        var start: Long,
        var end: Long,
        var owners: Int = 0,
        var delivered: Boolean = false,
    )

    internal data class Span(
        val source: Source,
        val start: Long,
        val length: Long,
    )

    /** Begins one independent HTTP response body, even when its media range was downloaded before. */
    fun newSource(): Source = synchronized(lock) { Source().also { sources.add(it) } }

    /** Records sequential body bytes delivered directly to Media3 without allocating provenance views. */
    fun deliveredDirect(bytes: Long) =
        synchronized(lock) {
            require(bytes >= 0)
            downloaded += bytes
            used += bytes
        }

    /** Retains the concatenation independently; the input handles remain owned by their callers. */
    fun concat(handles: List<Handle>): Handle =
        synchronized(lock) {
            require(handles.all { it.ledger === this && !it.closed })
            retain(handles.flatMap { it.spans })
        }

    /** Reads counters without scanning payloads; finalized sources no longer contribute metadata. */
    fun snapshot(): Snapshot =
        synchronized(lock) {
            Snapshot(
                downloaded,
                used,
                downloaded - used - discarded,
                discarded,
                discardedKinds.toMap(),
                sources.size,
                sources.sumOf { it.ranges.size },
            )
        }

    /** Network ownership protects every received byte until [finish] is called. */
    inner class Source internal constructor() {
        private var open = true
        private var finishReason = DownloadOverheadKind.Other
        private var receivedBytes = 0L
        internal val ranges = mutableListOf<Range>()

        /** Records bytes returned by a body read. Adjacent equivalent ranges are merged. */
        fun received(count: Long) =
            synchronized(lock) {
                check(open)
                require(count >= 0 && count <= Long.MAX_VALUE - receivedBytes)
                if (count > 0) {
                    ranges.add(Range(receivedBytes, receivedBytes + count))
                    receivedBytes += count
                    downloaded += count
                    compact()
                }
            }

        /** Retains a received region before the response body's original ownership ends. */
        fun view(
            offset: Long,
            length: Long,
        ): Handle =
            synchronized(lock) {
                check(open)
                checkBounds(offset, length, receivedBytes)
                retain(if (length == 0L) emptyList() else listOf(Span(this, offset, length)))
            }

        /** Ends network ownership exactly once; surviving copied spans remain pending or used. */
        fun finish(reason: DownloadOverheadKind = DownloadOverheadKind.Other) =
            synchronized(lock) {
                if (open) {
                    open = false
                    finishReason = reason
                    settle(reason)
                }
            }

        private fun split(at: Long) {
            val index = ranges.indexOfFirst { it.start < at && at < it.end }
            if (index >= 0) {
                val old = ranges[index]
                ranges.add(index + 1, old.copy(start = at))
                old.end = at
            }
        }

        internal fun change(
            start: Long,
            length: Long,
            operation: (Range) -> Unit,
        ) {
            split(start)
            split(start + length)
            ranges.forEach { if (it.start >= start && it.end <= start + length) operation(it) }
        }

        internal fun compact() {
            var index = ranges.lastIndex
            while (index > 0) {
                val left = ranges[index - 1]
                val right = ranges[index]
                if (left.end == right.start && left.owners == right.owners && left.delivered == right.delivered) {
                    left.end = right.end
                    ranges.removeAt(index)
                }
                index--
            }
        }

        internal fun settle(reason: DownloadOverheadKind) {
            if (!open) {
                val iterator = ranges.iterator()
                while (iterator.hasNext()) {
                    val range = iterator.next()
                    if (range.owners == 0) {
                        if (!range.delivered) {
                            val size = range.end - range.start
                            discarded += size
                            val category = if (reason == DownloadOverheadKind.Other) finishReason else reason
                            discardedKinds[category] = (discardedKinds[category] ?: 0L) + size
                        }
                        iterator.remove()
                    }
                }
                if (ranges.isEmpty()) sources.remove(this)
            }
            compact()
        }
    }

    /** An owned byte sequence whose spans may originate from multiple attempts. */
    inner class Handle internal constructor(
        internal val spans: List<Span>,
    ) {
        internal val ledger = this@ExactTransferLedger
        internal var closed = false

        /** Total bytes described by this view. */
        val length: Long = spans.sumOf { it.length }

        /** Creates another owner without copying payload data. */
        fun slice(
            offset: Long,
            length: Long,
        ): Handle =
            synchronized(lock) {
                check(!closed)
                retain(select(offset, length))
            }

        /** Counts each source byte at most once, including cache replay and overlapping copies. */
        fun delivered(
            offset: Long,
            length: Long,
        ) = synchronized(lock) {
            check(!closed)
            select(offset, length).forEach { span ->
                span.source.change(span.start, span.length) { range ->
                    if (!range.delivered) {
                        used += range.end - range.start
                        range.delivered = true
                    }
                }
                span.source.compact()
            }
        }

        /** Releases ownership exactly once; delivered bytes never become overhead. */
        fun close(reason: DownloadOverheadKind = DownloadOverheadKind.Other) =
            synchronized(lock) {
                if (!closed) {
                    closed = true
                    spans.forEach { span ->
                        span.source.change(span.start, span.length) { range ->
                            check(range.owners > 0)
                            range.owners--
                        }
                        span.source.settle(reason)
                    }
                }
            }

        private fun select(
            offset: Long,
            length: Long,
        ): List<Span> {
            checkBounds(offset, length, this.length)
            var position = 0L
            return spans.mapNotNull { span ->
                val start = maxOf(offset, position)
                val end = minOf(offset + length, position + span.length)
                val result = if (start < end) Span(span.source, span.start + start - position, end - start) else null
                position += span.length
                result
            }
        }
    }

    private fun retain(spans: List<Span>): Handle {
        spans.forEach { span ->
            span.source.change(span.start, span.length) { it.owners++ }
            span.source.compact()
        }
        return Handle(spans)
    }

    private fun checkBounds(
        offset: Long,
        length: Long,
        size: Long,
    ) {
        require(offset >= 0 && length >= 0 && offset <= size && length <= size - offset)
    }
}
