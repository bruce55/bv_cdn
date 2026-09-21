package dev.frost819.newbv.player.download

import kotlin.random.Random

/** Session-wide startup probe allocation; callers provide distinct CDN candidates per track. */
internal class StartupProbePolicy(
    maxRequests: Int,
    private val availableTracks: Set<DownloadTrack>,
    private val random: Random = Random.Default,
) {
    private class TrackState {
        var candidates: Int? = null
        var ended = false
        var launched = 0
        var inFlight = 0
        var successes = 0
    }

    private val target = maxRequests.coerceIn(2, 64)
    private val capacity = if (target >= 3) target - 1 else target
    private val audioPriorityTarget = (target + 1) / 2
    private val states = availableTracks.associateWith { TrackState() }
    private val initialAudio =
        if (DownloadTrack.Audio !in availableTracks) {
            0
        } else {
            if (DownloadTrack.Video in availableTracks) {
                (target / 4).coerceAtLeast(1)
            } else {
                capacity
            }
        }
    private val initial =
        availableTracks.associateWith { kind ->
            if (kind == DownloadTrack.Audio) initialAudio else capacity - initialAudio
        }
    private var nextTrack: DownloadTrack? = null

    /** Announces how many distinct candidates exist, once per participating track. */
    @Synchronized
    fun register(
        kind: DownloadTrack,
        candidateCount: Int,
    ) {
        val state = requireNotNull(states[kind])
        check(state.candidates == null) { "Startup track already registered" }
        state.candidates = candidateCount.coerceAtLeast(0)
    }

    private fun initialPending(kind: DownloadTrack): Boolean {
        val state = states.getValue(kind)
        if (state.ended) return false
        val candidates = state.candidates ?: return true
        return state.launched < minOf(initial.getValue(kind), candidates)
    }

    private fun canLaunch(kind: DownloadTrack): Boolean {
        val state = states.getValue(kind)
        return !state.ended && state.successes < target && state.launched < (state.candidates ?: 0)
    }

    /** Reserves one global probe slot; false asks this track's coordinator to wait. */
    @Synchronized
    fun tryAcquire(kind: DownloadTrack): Boolean {
        val state = requireNotNull(states[kind])
        if (!canLaunch(kind) || states.values.sumOf { it.inFlight } >= capacity) return false
        if (availableTracks.any(::initialPending)) {
            if (!initialPending(kind)) return false
        } else {
            if (nextTrack?.let(::canLaunch) != true) nextTrack = null
            if (nextTrack == null) {
                val audio = states[DownloadTrack.Audio]
                nextTrack =
                    if (audio != null && audio.successes < audioPriorityTarget && canLaunch(DownloadTrack.Audio)) {
                        DownloadTrack.Audio
                    } else {
                        val eligible = availableTracks.filter(::canLaunch)
                        if (eligible.isEmpty()) return false
                        eligible[random.nextInt(eligible.size)]
                    }
            }
            if (nextTrack != kind) return false
            nextTrack = null
        }
        state.launched++
        state.inFlight++
        return true
    }

    /** Releases a completed request, counting only a fully validated response as success. */
    @Synchronized
    fun completed(
        kind: DownloadTrack,
        valid: Boolean,
    ) {
        val state = requireNotNull(states[kind])
        check(state.inFlight > 0) { "No startup reservation to complete" }
        state.inFlight--
        if (valid) state.successes = (state.successes + 1).coerceAtMost(target)
    }

    /** Rolls back a reservation that never dispatched, keeping its candidate available. */
    @Synchronized
    fun abandon(kind: DownloadTrack) {
        val state = requireNotNull(states[kind])
        check(state.inFlight > 0) { "No startup reservation to abandon" }
        state.inFlight--
        state.launched--
    }

    /** Stops scheduling a closed or obsolete track; dispatched requests still release normally. */
    @Synchronized
    fun endTrack(kind: DownloadTrack) {
        requireNotNull(states[kind]).ended = true
        if (nextTrack == kind) nextTrack = null
    }

    /** True once its success target is met, or every finite candidate has completed. */
    @Synchronized
    fun isTrackDone(kind: DownloadTrack): Boolean {
        val state = requireNotNull(states[kind])
        return state.ended ||
            state.successes >= target ||
            (state.candidates != null && state.launched >= requireNotNull(state.candidates) && state.inFlight == 0)
    }

    /** True when all source tracks have finished probing. */
    @Synchronized
    fun isFinished(): Boolean = availableTracks.all(::isTrackDone)

    /** Currently reserved or dispatched startup requests for this track. */
    @Synchronized
    fun inFlightCount(kind: DownloadTrack): Int = requireNotNull(states[kind]).inFlight

    /** Fully validated distinct-CDN samples collected for this track. */
    @Synchronized
    fun validCount(kind: DownloadTrack): Int = requireNotNull(states[kind]).successes
}
