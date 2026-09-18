package dev.frost819.newbv.player.download

/** Automatic route preference; pinned mode never silently changes the selected host. */
enum class CdnMode { Mainland, Overseas, Pinned }

/** Independent switches for transport acceleration and its optional visualization. */
data class ParallelDownloadConfig(
    val enabled: Boolean = false,
    val maxRequests: Int = 4,
    val mode: CdnMode = CdnMode.Mainland,
    val pinnedHost: String = "",
    val visualizationEnabled: Boolean = false,
    val requestHeaders: Map<String, String> = emptyMap(),
)

/** Track identity used for scheduling and the two progress-bar lanes. */
enum class DownloadTrack { Video, Audio }

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
)

/** Throttled presentation state; activeRequests includes audio/video and retry attempts. */
data class DownloadSnapshot(
    val activeRequests: Int = 0,
    val maxRequests: Int = 0,
    val blocks: List<DownloadBlock> = emptyList(),
)
