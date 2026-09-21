package dev.frost819.newbv.player.download

import java.util.TreeMap

/** Coalesces session byte intervals without allocating one record per player read. Caller owns locking. */
internal class DownloadByteLedger {
    private val intervals = TreeMap<Long, Long>()

    fun add(
        start: Long,
        end: Long,
    ) {
        if (start < 0 || end < start) return
        var left = start
        var right = end
        intervals.floorEntry(left)?.let { previous ->
            if (touches(previous.value, left)) {
                left = previous.key
                right = maxOf(right, previous.value)
                intervals.remove(previous.key)
            }
        }
        var next = intervals.ceilingEntry(left)
        while (next != null && touches(right, next.key)) {
            right = maxOf(right, next.value)
            intervals.remove(next.key)
            next = intervals.ceilingEntry(left)
        }
        intervals[left] = right
    }

    fun totalBytes(): Long =
        intervals.entries.fold(0L) { sum, range -> saturatedByteSum(sum, length(range.key, range.value)) }

    /** Immutable interval values for mapping current reader delivery to media time. */
    fun ranges(): List<LongRange> = intervals.map { (start, end) -> start..end }

    /** Counts the union of resident/received bytes that this consumed-byte ledger does not contain. */
    fun unconsumedBytes(ranges: List<LongRange>): Long {
        val union = DownloadByteLedger()
        ranges.forEach { union.add(it.first, it.last) }
        var total = 0L
        for ((start, end) in union.intervals) {
            var cursor = start
            var covered = intervals.floorEntry(cursor)?.takeIf { it.value >= cursor } ?: intervals.ceilingEntry(cursor)
            var exhausted = false
            while (covered != null && covered.key <= end) {
                if (covered.key > cursor) total = saturatedByteSum(total, covered.key - cursor)
                if (covered.value >= end) {
                    exhausted = true
                    break
                }
                cursor = maxOf(cursor, covered.value + 1)
                covered = intervals.higherEntry(covered.key)
            }
            if (!exhausted) total = saturatedByteSum(total, length(cursor, end))
        }
        return total
    }

    private fun touches(
        end: Long,
        start: Long,
    ): Boolean = start <= end || start - end == 1L

    private fun length(
        start: Long,
        end: Long,
    ): Long = saturatedByteSum(end - start, 1)
}

/** Nonnegative byte counters saturate instead of wrapping on exceptionally long sessions. */
internal fun saturatedByteSum(
    first: Long,
    second: Long,
): Long = if (second > Long.MAX_VALUE - first) Long.MAX_VALUE else first + second
