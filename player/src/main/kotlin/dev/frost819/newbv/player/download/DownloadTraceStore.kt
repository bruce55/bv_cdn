package dev.frost819.newbv.player.download

/** One redacted scheduler event; sequence numbers remain monotonic across capture toggles. */
data class DownloadTraceEvent(
    val sequence: Long,
    val timeMs: Long,
    val session: String,
    val type: String,
    val fields: Map<String, String>,
)

/** Bounded event page. A cursor older than [oldestSequence] indicates evicted history. */
data class DownloadTraceSnapshot(
    val enabled: Boolean,
    val oldestSequence: Long,
    val nextSequence: Long,
    val events: List<DownloadTraceEvent>,
)

/**
 * Process-scoped, explicitly enabled scheduler recording, supplied by dependency injection.
 * Retains at most 4096 events and roughly 2 MiB of UTF-16 text, never media bodies.
 * Fields must contain diagnostic primitives only, never request URLs or authentication data.
 */
class DownloadTraceStore(
    private val maxEvents: Int = 4096,
    private val maxCharacters: Int = 1_048_576,
    private val memoryEventSink: (DownloadTraceEvent) -> Unit = {},
    private val bufferingEnabled: () -> Boolean = { false },
    private val bufferingSink: (String, DownloadTraceSnapshot) -> Unit = { _, _ -> },
) {
    init {
        require(maxEvents > 0 && maxCharacters >= 4096)
    }

    @Volatile private var liveEnabled = false
    private val bufferingPolicy = BufferingCapturePolicy()
    private var bufferingSession: String? = null
    private val captureId =
        java.util.UUID
            .randomUUID()
            .toString()

    /** Whether the separate live-server capture toggle is enabled. */
    val liveCaptureEnabled: Boolean get() = liveEnabled

    /** Recording is active for either live logging or persistent buffering reports. */
    val enabled: Boolean get() = liveEnabled || bufferingEnabled()
    private val events = java.util.ArrayDeque<DownloadTraceEvent>()
    private var characters = 0
    private var sequence = 0L

    /** Enables recording for this process; disabling preserves the final bounded capture for download. */
    @Synchronized
    fun setEnabled(value: Boolean) {
        liveEnabled = value
    }

    /** Adds a bounded event. Callers should check [enabled] before building expensive fields. */
    @Synchronized
    fun record(
        session: String,
        type: String,
        fields: Map<String, Any?>,
    ) {
        if (!enabled) {
            bufferingPolicy.sample(false, false, 0)
            return
        }
        val safeFields = linkedMapOf<String, String>()

        fun append(
            key: String,
            value: Any?,
            depth: Int = 0,
        ) {
            if (safeFields.size >= 256) return
            when {
                sensitive.containsMatchIn(key) -> safeFields[key.take(128)] = "[redacted]"
                depth < 3 && value is Map<*, *> ->
                    value.entries.take(40).forEach { (child, item) ->
                        if (child is String) append("$key.${child.take(64)}", item, depth + 1)
                    }
                depth < 3 && value is List<*> ->
                    value.take(20).forEachIndexed { index, item ->
                        append("$key.$index", item, depth + 1)
                    }
                else ->
                    safeFields[key.take(128)] =
                        when (value) {
                            null -> "null"
                            is Number, is Boolean, is Enum<*>, is String -> redact(value.toString()).take(1024)
                            else -> "[unsupported]"
                        }
            }
        }
        fields.entries.take(40).forEach { (key, value) -> append(key.take(64), value) }
        val event =
            DownloadTraceEvent(
                ++sequence,
                System.currentTimeMillis(),
                redact(session).take(64),
                redact(type).take(64),
                safeFields,
            )
        events.addLast(event)
        characters += size(event)
        while (events.size > maxEvents || characters > maxCharacters) characters -= size(events.removeFirst())
        if (event.type == "player_sample") {
            if (bufferingSession != event.session) {
                bufferingPolicy.sample(false, false, 0)
                bufferingSession = event.session
            }
            val now = event.fields["sampleElapsedMs"]?.toLongOrNull() ?: 0
            val buffering = event.fields["state"] == "2" && event.fields["playWhenReady"] == "true"
            bufferingPolicy.sample(bufferingEnabled(), buffering, now)?.let { incident ->
                runCatching { bufferingSink("${captureId}_$incident", snapshot()) }
            }
        }
        if (event.type in persistedMemoryTypes) {
            // The injected sink only enqueues bounded work; disk I/O must never run on this caller.
            runCatching { memoryEventSink(event) }
        }
    }

    /** Returns an immutable page containing events newer than the supplied exclusive cursor. */
    @Synchronized
    fun snapshot(after: Long = 0): DownloadTraceSnapshot =
        DownloadTraceSnapshot(
            enabled,
            events.peekFirst()?.sequence ?: (sequence + 1),
            sequence + 1,
            events.filter { it.sequence > after },
        )

    private fun size(event: DownloadTraceEvent): Int =
        96 + event.session.length + event.type.length + event.fields.entries.sumOf { it.key.length + it.value.length }

    private fun redact(value: String): String = url.replace(value, "[url]")

    private companion object {
        val persistedMemoryTypes = setOf("memory_sample", "memory_trim", "memory_exit_history", "session_capacity")
        val sensitive = Regex("cookie|authorization|token|password|secret|query|url|path", RegexOption.IGNORE_CASE)
        val url = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)
    }
}
