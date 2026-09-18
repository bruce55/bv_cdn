package dev.frost819.newbv.player.download

import java.io.IOException

/** Validated inclusive HTTP range, always with a known representation length. */
internal data class RangeResponse(
    val start: Long,
    val end: Long,
    val total: Long,
) {
    companion object {
        /** Rejects shifted, overlong, unknown-total, and inconsistent range responses. */
        fun parse(
            header: String?,
            start: Long,
            end: Long,
            total: Long?,
        ): RangeResponse {
            val match =
                Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(header.orEmpty())
                    ?: throw IOException("Missing or malformed Content-Range")
            val numbers = match.groupValues.drop(1).map { it.toLongOrNull() ?: throw IOException("Range overflow") }
            val range = RangeResponse(numbers[0], numbers[1], numbers[2])
            if (range.start != start ||
                range.total <= start ||
                range.end != minOf(end, range.total - 1) ||
                range.end < range.start ||
                (total != null && total != range.total)
            ) {
                throw IOException("Inconsistent media range")
            }
            return range
        }
    }
}
