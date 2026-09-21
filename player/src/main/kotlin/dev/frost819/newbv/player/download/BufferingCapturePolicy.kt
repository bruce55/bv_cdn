package dev.frost819.newbv.player.download

/** Captures sustained buffering once after three seconds, then every ten seconds and on recovery. */
internal class BufferingCapturePolicy {
    private var started: Long? = null
    private var lastSaved: Long? = null

    /** Returns the incident start for a save, using a monotonic playback sample clock. */
    fun sample(
        enabled: Boolean,
        buffering: Boolean,
        now: Long,
    ): Long? {
        if (!enabled) {
            started = null
            lastSaved = null
            return null
        }
        if (!buffering) {
            val finished = started.takeIf { lastSaved != null }
            started = null
            lastSaved = null
            return finished
        }
        val start = started ?: now.also { started = it }
        if (now - start < 3000 || lastSaved?.let { now - it < 10_000 } == true) return null
        lastSaved = now
        return start
    }
}
