package dev.frost819.newbv.player.download

/** Bounded active-slot identities and proven discarded-byte counters. Caller serializes access. */
internal class DownloadWorkTelemetry(
    private val maxSlots: Int,
) {
    private val active = linkedMapOf<Long, DownloadWorker>()
    private val discarded = LongArray(DownloadOverheadKind.entries.size)

    fun started(
        attemptId: Long,
        host: String,
        track: DownloadTrack,
        kind: DownloadWorkKind,
    ) {
        if (attemptId in active) return
        val occupied = active.values.map { it.slot }.toSet()
        val slot = (0 until maxSlots.coerceIn(0, 64)).firstOrNull { it !in occupied } ?: return
        active[attemptId] = DownloadWorker(slot, attemptId, host, track, kind)
    }

    fun finished(attemptId: Long) {
        active.remove(attemptId)
    }

    fun workers(): List<DownloadWorker> = active.values.sortedBy { it.slot }

    fun discarded(
        kind: DownloadOverheadKind,
        bytes: Long,
    ) {
        if (bytes <= 0 || kind == DownloadOverheadKind.Other) return
        discarded[kind.ordinal] = saturatedByteSum(discarded[kind.ordinal], bytes)
    }

    fun overhead(total: Long): List<DownloadOverhead> {
        var remaining = total.coerceAtLeast(0)
        val result = mutableListOf<DownloadOverhead>()
        // Transport/monitor residency snapshots can cross in flight. Never classify more than
        // the observed waste; omitted evidence remains in the bounded counters for the next frame.
        DownloadOverheadKind.entries.filter { it != DownloadOverheadKind.Other }.forEach { kind ->
            val bytes = minOf(remaining, discarded[kind.ordinal])
            if (bytes > 0) result.add(DownloadOverhead(kind, bytes))
            remaining -= bytes
        }
        if (remaining > 0) result.add(DownloadOverhead(DownloadOverheadKind.Other, remaining))
        return result
    }

    fun clear() {
        active.clear()
        discarded.fill(0)
    }
}
