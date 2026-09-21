package dev.frost819.newbv.player.impl.exo

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import dev.frost819.newbv.player.download.PlaybackBufferBudget

/** Keeps default timing but bounds sample buffering within the shared playback heap budget. */
@UnstableApi
internal class MeasuredLoadControl(
    private val budget: PlaybackBufferBudget = PlaybackBufferBudget(),
) : DefaultLoadControl() {
    @Volatile private var selectedTargetBytes = 0

    @Volatile private var continueLoading: Boolean? = null

    @Volatile private var loadingBufferUs = 0L

    override fun shouldContinueLoading(parameters: androidx.media3.exoplayer.LoadControl.Parameters): Boolean =
        super.shouldContinueLoading(parameters).also {
            loadingBufferUs = parameters.bufferedDurationUs
            continueLoading = it
        }

    override fun onPrepared(playerId: PlayerId) {
        super.onPrepared(playerId)
        selectedTargetBytes = DEFAULT_MIN_BUFFER_SIZE
    }

    override fun calculateTargetBufferBytes(trackSelectionArray: Array<out ExoTrackSelection?>): Int =
        minOf(super.calculateTargetBufferBytes(trackSelectionArray), budget.media3Bytes)
            .also { selectedTargetBytes = it }

    override fun onStopped(playerId: PlayerId) {
        super.onStopped(playerId)
        selectedTargetBytes = 0
    }

    override fun onReleased(playerId: PlayerId) {
        super.onReleased(playerId)
        selectedTargetBytes = 0
    }

    /** Allocator usage for downloader pressure accounting, without constructing a diagnostic map. */
    fun allocatedBytes(): Long = allocator.totalBytesAllocated.toLong()

    /** In-use allocator bytes exclude the allocator's free pool and codec/surface allocations. */
    fun memoryFields(): Map<String, Any?> =
        mapOf(
            "continueLoading" to continueLoading,
            "loadingDecisionBufferUs" to loadingBufferUs,
            "allocatorInUseBytes" to allocator.totalBytesAllocated,
            "allocatorSegmentBytes" to allocator.individualAllocationLength,
            "selectedTargetBytes" to selectedTargetBytes,
            "budgetBytes" to budget.media3Bytes,
            "combinedBufferBudgetBytes" to budget.totalBytes,
            "minimumBufferMs" to DEFAULT_MIN_BUFFER_MS,
            "maximumBufferMs" to DEFAULT_MAX_BUFFER_MS,
            "backBufferMs" to DEFAULT_BACK_BUFFER_DURATION_MS,
            "scope" to "sample_queue_in_use_excludes_free_pool_codec_surfaces",
        )
}
