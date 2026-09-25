package dev.frost819.newbv.player

/** Selection policy captured for one playback source; parallel transport owns its own probes and recovery. */
class CdnPlaybackPolicy(
    private val parallel: Boolean,
    manualHost: String,
    autoSelect: Boolean,
) {
    private val pinnedHost = CdnUrls.normalizeHost(manualHost)

    /** Only automatic single-stream playback may restart on another candidate after an error. */
    val automatic: Boolean = !parallel && pinnedHost.isEmpty() && autoSelect

    /** Keeps signed API candidates intact for parallel mode, otherwise applies the single-stream choice. */
    suspend fun candidates(
        urls: List<String>,
        selector: CdnSelector,
    ): List<String> {
        val originals = CdnUrls.distinct(urls)
        if (parallel) return originals
        val preferred = CdnUrls.officialCandidates(originals)
        return when {
            pinnedHost.isNotEmpty() -> preferred.take(1).map { CdnUrls.override(it, pinnedHost) }
            automatic -> selector.rank(preferred)
            else -> preferred.take(1)
        }
    }
}
