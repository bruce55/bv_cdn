package dev.frost819.newbv.player.download

import java.util.IdentityHashMap

/** Short-lived diagnostic inventory of known backing arrays; never copies or serializes payloads. */
internal class KnownDownloadStorage {
    private val all = IdentityHashMap<ByteArray, Boolean>()
    private val groups = mutableMapOf<String, IdentityHashMap<ByteArray, Boolean>>()

    /** Counts a shared backing array once globally and once within each overlapping ownership category. */
    fun add(
        bytes: ByteArray,
        track: DownloadTrack,
        category: String,
    ) {
        if (bytes.isEmpty()) return
        all[bytes] = true
        groups.getOrPut(category) { IdentityHashMap() }[bytes] = true
        groups.getOrPut(track.name.lowercase()) { IdentityHashMap() }[bytes] = true
    }

    /** Primitive fields only. Per-category totals overlap and must not be added to knownBytes. */
    fun fields(): Map<String, Any?> =
        buildMap {
            put("knownBytes", all.keys.sumOf { it.size.toLong() })
            put("knownArrays", all.size)
            for (group in listOf("cache", "reader", "attempt", "video", "audio", "consumedCurrent")) {
                put("${group}Bytes", groups[group]?.keys?.sumOf { it.size.toLong() } ?: 0L)
            }
            put("scope", "sampled_known_arrays_excludes_transient_copies")
        }
}
