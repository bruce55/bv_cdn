package dev.frost819.newbv.player.download

/** Readiness phase survives pauses, distinguishing a fresh seek from genuine playback starvation. */
internal class PlaybackSchedulingTracker {
    private enum class Phase { Fresh, Established, SeekingFresh, SeekingEstablished }

    private var phase = Phase.Fresh
    var state: PlaybackSchedulingState = PlaybackSchedulingState.Starting
        private set

    val everReady: Boolean get() = phase == Phase.Established || phase == Phase.SeekingEstablished
    val awaitingReadiness: Boolean get() = phase != Phase.Established
    val rebuffering: Boolean get() = state == PlaybackSchedulingState.Buffering

    fun beginSeek() {
        phase = if (everReady) Phase.SeekingEstablished else Phase.SeekingFresh
        state = PlaybackSchedulingState.Seeking
    }

    fun update(
        playWhenReady: Boolean,
        isPlaying: Boolean,
        isBuffering: Boolean,
        isReady: Boolean,
    ) {
        if (isReady || isPlaying) phase = Phase.Established
        state =
            when {
                !playWhenReady -> PlaybackSchedulingState.Paused
                phase == Phase.Fresh -> PlaybackSchedulingState.Starting
                phase == Phase.SeekingFresh || phase == Phase.SeekingEstablished -> PlaybackSchedulingState.Seeking
                isBuffering -> PlaybackSchedulingState.Buffering
                else -> PlaybackSchedulingState.Playing
            }
    }
}
