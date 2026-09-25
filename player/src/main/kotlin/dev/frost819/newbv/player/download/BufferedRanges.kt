package dev.frost819.newbv.player.download

/** Intersects the monitor's sorted, merged per-track intervals without filling missing-track gaps. */
internal fun commonBufferedRanges(
    available: List<DownloadAvailableRange>,
    requiredTracks: Set<DownloadTrack>,
): List<DownloadBufferedRange> {
    var common: List<DownloadBufferedRange>? = null
    for (track in requiredTracks) {
        val ranges =
            available.filter { it.track == track }.map {
                DownloadBufferedRange(it.startTimeMs, it.endTimeMs)
            }
        val previous = common
        if (previous == null) {
            common = ranges
            continue
        }
        val intersection = mutableListOf<DownloadBufferedRange>()
        var a = 0
        var b = 0
        while (a < previous.size && b < ranges.size) {
            val left = previous[a]
            val right = ranges[b]
            val start = maxOf(left.startTimeMs, right.startTimeMs)
            val end = minOf(left.endTimeMs, right.endTimeMs)
            if (start < end) intersection.add(DownloadBufferedRange(start, end))
            if (left.endTimeMs <= right.endTimeMs) a++ else b++
        }
        common = intersection
    }
    return common.orEmpty()
}
