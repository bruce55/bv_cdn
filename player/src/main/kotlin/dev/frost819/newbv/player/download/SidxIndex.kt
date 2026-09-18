package dev.frost819.newbv.player.download

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A media segment described by the file's SIDX index. */
internal data class IndexedSegment(
    val startByte: Long,
    val endByte: Long,
    val startTimeMs: Long,
    val endTimeMs: Long,
)

/** Parses complete top-level SIDX boxes; partial or unsupported indexes return no mapping. */
internal object SidxIndex {
    fun parse(bytes: ByteArray): List<IndexedSegment> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        var offset = 0
        while (offset <= bytes.size - 8) {
            val shortSize = uint(buffer, offset)
            val type = buffer.getInt(offset + 4)
            val headerSize = if (shortSize == 1L) 16 else 8
            if (offset > bytes.size - headerSize) return emptyList()
            val size =
                when (shortSize) {
                    0L -> (bytes.size - offset).toLong()
                    1L -> buffer.getLong(offset + 8)
                    else -> shortSize
                }
            if (size < headerSize || size > bytes.size - offset) return emptyList()
            if (type == 0x73696478) return parseBox(buffer, offset, size.toInt(), headerSize)
            offset += size.toInt()
        }
        return emptyList()
    }

    private fun parseBox(
        buffer: ByteBuffer,
        start: Int,
        size: Int,
        header: Int,
    ): List<IndexedSegment> {
        var cursor = start + header
        val end = start + size
        if (cursor > end - 12) return emptyList()
        val version = buffer.get(cursor).toInt() and 0xff
        val scale = uint(buffer, cursor + 8)
        cursor += 12
        if (scale == 0L || version !in 0..1) return emptyList()
        val timeBytes = if (version == 0) 8 else 16
        if (cursor > end - timeBytes - 4) return emptyList()
        val earliest = if (version == 0) uint(buffer, cursor) else buffer.getLong(cursor)
        val firstOffset = if (version == 0) uint(buffer, cursor + 4) else buffer.getLong(cursor + 8)
        if (earliest < 0 || firstOffset < 0 || firstOffset > Long.MAX_VALUE - end) return emptyList()
        cursor += timeBytes + 2
        val count = buffer.getShort(cursor).toInt() and 0xffff
        cursor += 2
        if (count == 0 || count > (end - cursor) / 12) return emptyList()
        var bytePosition = end.toLong() + firstOffset
        var time = earliest
        val result = ArrayList<IndexedSegment>(count)
        repeat(count) {
            val reference = uint(buffer, cursor)
            val length = reference and 0x7fffffff
            val duration = uint(buffer, cursor + 4)
            // Nested SIDX references need a further index fetch; do not guess their layout.
            if (reference ushr 31 != 0L || length == 0L || duration == 0L) return emptyList()
            if (bytePosition > Long.MAX_VALUE - length || time > Long.MAX_VALUE - duration) return emptyList()
            val endTime = time + duration
            val startMs = (time.toDouble() * 1000 / scale).toLong()
            val endMs = (endTime.toDouble() * 1000 / scale).toLong()
            result += IndexedSegment(bytePosition, bytePosition + length - 1, startMs, endMs)
            bytePosition += length
            time = endTime
            cursor += 12
        }
        return result
    }

    private fun uint(
        buffer: ByteBuffer,
        offset: Int,
    ): Long = buffer.getInt(offset).toLong() and 0xffffffffL
}
