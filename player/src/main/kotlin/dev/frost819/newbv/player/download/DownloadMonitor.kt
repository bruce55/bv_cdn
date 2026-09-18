package dev.frost819.newbv.player.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/** Session-owned, bounded telemetry; updates are coalesced to at most ten per second. */
class DownloadMonitor(
    private val config: ParallelDownloadConfig,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {
    private val mutableSnapshots = MutableStateFlow(DownloadSnapshot(maxRequests = config.maxRequests.coerceIn(2, 8)))

    /** Presentation state; network workers never invoke Compose directly. */
    val snapshots: StateFlow<DownloadSnapshot> = mutableSnapshots.asStateFlow()
    private val blocks = linkedMapOf<Long, DownloadBlock>()
    private val prefixes = mutableMapOf<DownloadTrack, ByteArrayOutputStream>()
    private val indexes = mutableMapOf<DownloadTrack, List<IndexedSegment>>()
    private var sequence = 0L
    private var active = 0
    private var publishing = false
    private var closed = false

    /** Registers a planned inclusive byte range, returning its session-local identity. */
    @Synchronized
    fun plan(
        track: DownloadTrack,
        start: Long,
        end: Long,
    ): Long {
        val id = ++sequence
        if (closed || !config.visualizationEnabled) return id
        while (blocks.size >= 256) {
            val removable =
                blocks.values.firstOrNull { it.state in listOf(DownloadBlockState.Complete, DownloadBlockState.Failed) }
                    ?: blocks.values.firstOrNull() ?: break
            blocks.remove(removable.id)
        }
        blocks[id] = DownloadBlock(id, track, start, end)
        publishSoon()
        return id
    }

    /** Updates one block's lifecycle without changing its byte bounds. */
    @Synchronized
    fun state(
        id: Long,
        state: DownloadBlockState,
    ) {
        if (closed) return
        blocks[id]?.let { blocks[id] = it.copy(state = state) }
        publishSoon()
    }

    /** Records bytes received for the current attempt, capped to the block's length. */
    @Synchronized
    fun progress(
        id: Long,
        receivedBytes: Long,
    ) {
        if (closed) return
        blocks[id]?.let {
            blocks[id] =
                it.copy(
                    receivedBytes = receivedBytes.coerceIn(0, it.endByte - it.startByte + 1),
                )
        }
        publishSoon()
    }

    /** Counts an actual started HTTP attempt, including retries and audio. */
    @Synchronized
    fun requestStarted() {
        if (closed) return
        active++
        publishSoon()
    }

    /** Ends an actual HTTP attempt. */
    @Synchronized
    fun requestFinished() {
        if (closed) return
        active = (active - 1).coerceAtLeast(0)
        publishSoon()
    }

    /** Collects only a bounded contiguous file prefix to discover the real media index. */
    @Synchronized
    fun recordBytes(
        track: DownloadTrack,
        start: Long,
        bytes: ByteArray,
    ) {
        if (closed || !config.visualizationEnabled || indexes.containsKey(track)) return
        val prefix = prefixes.getOrPut(track) { ByteArrayOutputStream() }
        val current = prefix.size()
        if (start < 0 || start > current || start >= MAX_PREFIX_BYTES) return
        val skip = (current - start).toInt()
        if (skip >= bytes.size || current >= MAX_PREFIX_BYTES) return
        val count = minOf(bytes.size - skip, MAX_PREFIX_BYTES - current)
        prefix.write(bytes, skip, count)
        val parsed = SidxIndex.parse(prefix.toByteArray())
        if (parsed.isNotEmpty()) {
            indexes[track] = parsed
            prefixes.remove(track)
            publishSoon()
        }
    }

    /** Removes abandoned ranges when a data source is closed or replaced. */
    @Synchronized
    fun discard(ids: Collection<Long>) {
        if (closed) return
        ids.forEach(blocks::remove)
        publishSoon()
    }

    @Synchronized
    private fun publishSoon() {
        if (closed || publishing || !config.visualizationEnabled) return
        publishing = true
        scope.launch {
            delay(100)
            synchronized(this@DownloadMonitor) {
                if (!closed) {
                    mutableSnapshots.value =
                        DownloadSnapshot(active, config.maxRequests.coerceIn(2, 8), blocks.values.map(::mapBlock))
                }
                publishing = false
            }
        }
    }

    private fun mapBlock(block: DownloadBlock): DownloadBlock {
        val segments = indexes[block.track] ?: return block
        val first =
            segments.firstOrNull { it.endByte >= block.startByte && it.startByte <= block.endByte } ?: return block
        val last =
            segments.lastOrNull { it.startByte <= block.endByte && it.endByte >= block.startByte } ?: return block

        // Segment boundaries are indexed; positions within each segment are approximate.
        fun timeAt(
            segment: IndexedSegment,
            position: Long,
        ): Long {
            val fraction = (position - segment.startByte).toDouble() / (segment.endByte - segment.startByte + 1)
            return segment.startTimeMs + ((segment.endTimeMs - segment.startTimeMs) * fraction).toLong()
        }
        return block.copy(
            startTimeMs = timeAt(first, maxOf(block.startByte, first.startByte)),
            endTimeMs =
                timeAt(
                    last,
                    minOf(block.endByte, last.endByte) + 1,
                ),
        )
    }

    /** Cancels pending publications and clears all state on playback replacement. */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        blocks.clear()
        prefixes.clear()
        indexes.clear()
        active = 0
        mutableSnapshots.value = DownloadSnapshot(maxRequests = config.maxRequests.coerceIn(2, 8))
    }

    private companion object {
        const val MAX_PREFIX_BYTES = 2 * 1024 * 1024
    }
}
