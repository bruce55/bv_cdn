package dev.frost819.newbv.player.download

/** Automatic route preference; pinned mode never silently changes the selected host. */
enum class CdnMode { Mainland, Overseas, Pinned }

/** Independent switches for transport acceleration and its optional visualization. */
data class ParallelDownloadConfig(
    val enabled: Boolean = false,
    val maxRequests: Int = 8,
    val minimumBlockKiB: Int = 0,
    val mode: CdnMode = CdnMode.Mainland,
    val pinnedHost: String = "",
    val visualizationEnabled: Boolean = false,
    val diagnosticsEnabled: Boolean = false,
    val requestHeaders: Map<String, String> = emptyMap(),
)

/** Track identity used for scheduling and the two progress-bar lanes. */
enum class DownloadTrack { Video, Audio }

/** Work occupying an actual HTTP slot, independent of its eventual useful or discarded outcome. */
enum class DownloadWorkKind { Ordinary, Probe, Exploration, Rescue }

/** Stable zero-based slot assignment for one active request; only a host name is exposed. */
data class DownloadWorker(
    val slot: Int,
    val attemptId: Long,
    val host: String,
    val track: DownloadTrack,
    val kind: DownloadWorkKind,
)

/** Proven reasons for discarded traffic; unknown attribution stays in [Other]. */
enum class DownloadOverheadKind { Probe, Exploration, Rescue, Failure, Cancelled, Other }

/** Body bytes known to be wasted, never total traffic merely associated with that work kind. */
data class DownloadOverhead(
    val kind: DownloadOverheadKind,
    val bytes: Long,
)

/** Startup/seek buffering is separate from interrupted playback, and never counts as rebuffering. */
enum class PlaybackSchedulingState { Starting, Playing, Seeking, Buffering, Paused }

/** All URLs describe the same selected media representation. */
data class MediaTrackSource(
    val id: String,
    val kind: DownloadTrack,
    val urls: List<String>,
)

/** A VOD source retains backup URLs and the video/part identity. */
data class VodPlaybackSource(
    val contentId: String,
    val video: MediaTrackSource?,
    val audio: MediaTrackSource?,
)

/** Lifecycle of one planned byte block. */
enum class DownloadBlockState { Planned, Active, Complete, Retrying, Failed }

/** Outcome of the exploratory request, independent of rescued playback data. */
enum class ExplorationState { None, Testing, Succeeded, Failed, Cancelled }

/** A byte block with optional index-derived media times; missing times must never be guessed. */
data class DownloadBlock(
    val id: Long,
    val track: DownloadTrack,
    val startByte: Long,
    val endByte: Long,
    val receivedBytes: Long = 0,
    val state: DownloadBlockState = DownloadBlockState.Planned,
    val startTimeMs: Long? = null,
    val endTimeMs: Long? = null,
    val exploration: ExplorationState = ExplorationState.None,
    val rescued: Boolean = false,
    val hadFailure: Boolean = false,
    val deadlineUrgency: Float = 0f,
)

/** A locally available media-time interval; the end is exclusive. Both storage sources look alike. */
data class DownloadAvailableRange(
    val track: DownloadTrack,
    val startTimeMs: Long,
    val endTimeMs: Long,
)

/** Body-copy accounting when [exact] is true; otherwise the legacy session file-range estimate. */
data class DownloadTransferStats(
    val downloadedBytes: Long = 0,
    val usedBytes: Long = 0,
    val pendingBytes: Long = 0,
    val overheadBytes: Long = 0,
    val overheadBreakdown: List<DownloadOverhead> =
        if (overheadBytes >
            0
        ) {
            listOf(DownloadOverhead(DownloadOverheadKind.Other, overheadBytes))
        } else {
            emptyList()
        },
    val exact: Boolean = false,
)

/**
 * Independent observed counters, not a partition of network traffic. Delivery includes replay;
 * unique delivery is a session file-range union. Resident/in-flight ranges can overlap each other
 * and previously delivered data. Discards include finalized HTTP losers, not later cache eviction.
 * Download counts HTTP body bytes read by the app, excluding headers and transport retransmission.
 */
data class DownloadTransferMeasurements(
    val downloadedBodyBytes: Long = 0,
    val deliveredToPlayerBytes: Long = 0,
    val uniqueDeliveredBytes: Long = 0,
    val residentUniqueBytes: Long = 0,
    val inFlightUniqueBytes: Long = 0,
    val retainedUniqueBytes: Long = 0,
    val confirmedTransportDiscardedBytes: Long = 0,
) {
    /** Primitive diagnostic values; these overlapping counters must not be added together. */
    fun fields(): Map<String, Any?> =
        mapOf(
            "downloadedBodyBytes" to downloadedBodyBytes,
            "deliveredToPlayerBytes" to deliveredToPlayerBytes,
            "uniqueDeliveredBytes" to uniqueDeliveredBytes,
            "repeatDeliveryBytes" to (deliveredToPlayerBytes - uniqueDeliveredBytes).coerceAtLeast(0),
            "residentUniqueBytes" to residentUniqueBytes,
            "inFlightUniqueBytes" to inFlightUniqueBytes,
            "retainedUniqueBytes" to retainedUniqueBytes,
            "confirmedTransportDiscardedBytes" to confirmedTransportDiscardedBytes,
            "scope" to
                "overlapping_counters_not_traffic_partition;delivery_is_not_playback;discard_excludes_cache_eviction",
        )
}

/** Throttled presentation state; activeRequests includes audio/video and retry attempts. */
data class DownloadSnapshot(
    val activeRequests: Int = 0,
    val maxRequests: Int = 0,
    val blocks: List<DownloadBlock> = emptyList(),
    val routes: List<DownloadRoute> = emptyList(),
    val history: List<DownloadSample> = emptyList(),
    val segments: List<DownloadSegmentStats> = emptyList(),
    val videoProbes: Int = 0,
    val audioProbes: Int = 0,
    val startupProbing: Boolean = false,
    val availableRanges: List<DownloadAvailableRange> = emptyList(),
    val transferStats: DownloadTransferStats = DownloadTransferStats(),
    val workers: List<DownloadWorker> = emptyList(),
    val waitReason: String? = null,
    val schedulingState: PlaybackSchedulingState = PlaybackSchedulingState.Starting,
    val transferMeasurements: DownloadTransferMeasurements = DownloadTransferMeasurements(),
)

/** Session-local host diagnostics, excluding signed paths, query strings and request headers. */
data class DownloadRoute(
    val host: String,
    val activeRequests: Int = 0,
    val successes: Int = 0,
    val failures: Int = 0,
    val lastFailure: String? = null,
    val bytesPerSecond: Long = 0,
    val cooldownRemainingMs: Long = 0,
    val attempts: Int = 0,
    val deadlineMisses: Int = 0,
    val rescuesProvided: Int = 0,
    val cancellations: Int = 0,
    val schedulingConfidence: Double = 1.0,
    val lastSampleBytes: Long = 0,
    val lastSampleDurationMs: Long = 0,
)

/** One bounded, monotonic chart sample; rates include idle gaps and rescue traffic. */
data class DownloadSample(
    val elapsedMs: Long,
    val activeRequests: Int,
    val bufferSeconds: Double,
    val meanVideoSegmentBytes: Double,
    val meanAudioSegmentBytes: Double,
    val hosts: Map<String, DownloadHostSample>,
    val explorations: Int,
    val rescues: Int,
    val failures: Int,
)

/** Host traffic and latest completed request measurement are deliberately separate curves. */
data class DownloadHostSample(
    val trafficBytesPerSecond: Double,
    val measuredBytesPerSecond: Double,
    val effectiveBytesPerSecond: Double,
)

/** Actual SIDX media-segment sizes, independent of request subdivision and retries. */
data class DownloadSegmentStats(
    val track: DownloadTrack,
    val count: Int,
    val averageBytes: Double,
)
