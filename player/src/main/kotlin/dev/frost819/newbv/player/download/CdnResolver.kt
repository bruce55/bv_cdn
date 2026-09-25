package dev.frost819.newbv.player.download

import dev.frost819.newbv.player.CdnUrls
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI
import kotlin.random.Random

/** Session-local route policy; signed paths and queries are retained verbatim. */
internal class CdnResolver(
    private val config: ParallelDownloadConfig,
    private val random: Random = Random.Default,
    private val trace: (String, Map<String, Any?>) -> Unit = { _, _ -> },
    private val traceEnabled: () -> Boolean = { false },
    private val nowNanos: () -> Long = System::nanoTime,
) {
    private data class Health(
        var failures: Int = 0,
        var until: Long = 0,
    )

    private data class Performance(
        val window: BlockSpeedWindow = BlockSpeedWindow(),
        var measuredAt: Long = 0,
        var concurrency: Double = 1.0,
        var latencyNanos: Double = 0.0,
    )

    private data class Evidence(
        var bytes: Long = 0,
        var samples: Int = 0,
        var updatedAt: Long = 0,
        var meaningfulAt: Long? = null,
    )

    /** One assigned request; identity preserves independent loads even for identical retry URLs. */
    internal class RouteAssignment(
        val url: String,
        val bytes: Long,
        val concurrentRequests: Int,
        /** Per-block forecast captured before dispatch; assigned load does not multiply the rate. */
        val estimatedTransferNanos: Long?,
        /** Speed-evidence epoch at dispatch; late pre-seek completions retain their original epoch. */
        val evidenceEpoch: Long,
    ) {
        var receivedBytes: Long = 0
        var rescued: Boolean = false
        var penaltyLevel: Int = 0
    }

    private val assigned = mutableMapOf<String, MutableSet<RouteAssignment>>()
    private val health = mutableMapOf<String, Health>()
    private val confidence = mutableMapOf<String, Double>()
    private val penaltyStrikes = mutableMapOf<String, Int>()
    private val lastRecovery = mutableMapOf<String, Long>()
    private val performance = mutableMapOf<String, Performance>()
    private var evidenceEpoch = 0L
    private val evidence = mutableMapOf<String, Evidence>()
    private val probedAt = mutableMapOf<String, Long>()
    private var lastExploration: Long? = null
    private var outageRecovery: RouteAssignment? = null
    private var recoveryNotBefore = 0L
    private val lastRankingTrace = mutableMapOf<DownloadTrack, Long>()

    private data class UrlMetadata(
        val host: String?,
        val routeKey: String,
        val mediaKey: String,
    )

    // Every access is under the resolver lock. A session has a fixed set of signed
    // representation URLs; parsing them on each comparator call serialized all workers.
    private val urlMetadata = mutableMapOf<String, UrlMetadata>()
    private val candidateUrls = mutableMapOf<MediaTrackSource, List<String>>()

    private fun metadata(url: String): UrlMetadata =
        urlMetadata.getOrPut(url) {
            url.toHttpUrlOrNull()?.let {
                UrlMetadata(it.host, "${it.host}:${it.port}", "${it.host}:${it.port}${it.encodedPath}")
            } ?: UrlMetadata(null, "", "")
        }

    // Audio throughput is not evidence about video cache residency, even on the same host.
    private fun mediaKey(url: String): String = metadata(url).mediaKey

    private fun routeKey(url: String): String = metadata(url).routeKey

    /** Ranks eligible routes by this block's predicted completion from finalized per-block speeds. */
    @Synchronized
    fun candidates(
        track: MediaTrackSource,
        bytes: Long = RangePartition.TARGET_BYTES,
        deadlineNanos: Long? = null,
    ): List<String> {
        val eligible = candidateUrls.getOrPut(track) { buildCandidateUrls(track) }
        val now = nowNanos()
        val ready = eligible.filter { (health[routeKey(it)]?.until ?: 0) <= now }.ifEmpty { eligible }
        if (ready.isEmpty()) {
            traceChoice("cdn.ranking", track, bytes, deadlineNanos, ready, null, "no_routes", now)
            return emptyList()
        }
        // Unknown routes are tested on a protected, distant half-range. Playback-critical
        // work keeps a proven route available. Media-position distance weights completed evidence.
        val estimates = ready.associateWith { predictedFinishNanos(it, bytes) }
        val ranked = ready.sortedBy { estimates[it] ?: Double.POSITIVE_INFINITY }
        if (deadlineNanos == null) {
            traceChoice(
                "cdn.ranking",
                track,
                bytes,
                null,
                ranked,
                ranked.first(),
                "estimated_finish_order",
                now,
                estimates,
            )
            return ranked
        }
        val measured = ranked.filter { estimates[it] != null }
        if (measured.isEmpty()) {
            traceChoice(
                "cdn.ranking",
                track,
                bytes,
                deadlineNanos,
                ranked,
                ranked.first(),
                "unmeasured_order",
                now,
                estimates,
            )
            return ranked
        }
        val feasible = measured.filter { requireNotNull(estimates[it]) <= deadlineNanos - now }

        fun unused(url: String): Boolean = assigned[routeKey(url)].isNullOrEmpty()
        val capableIdle = feasible.filter(::unused)
        val preferred =
            when {
                capableIdle.isNotEmpty() -> capableIdle[random.nextInt(capableIdle.size)]
                feasible.isNotEmpty() -> feasible.first()
                else -> measured.first()
            }
        traceChoice(
            "cdn.ranking",
            track,
            bytes,
            deadlineNanos,
            ranked,
            preferred,
            when {
                capableIdle.isNotEmpty() -> "random_feasible_idle"
                feasible.isNotEmpty() -> "fastest_feasible_busy"
                unused(preferred) -> "fastest_idle_deadline_unreachable"
                else -> "fastest_busy_deadline_unreachable"
            },
            now,
            estimates,
        )
        return listOf(preferred) + ranked.filter { it != preferred }
    }

    // Preserve the original signed strings byte-for-byte; only the authority changes.
    // Health and load remain dynamic and are deliberately not part of this cache.
    private fun buildCandidateUrls(track: MediaTrackSource): List<String> {
        val originals = track.urls.filter { metadata(it).host != null }.distinct()
        val donor =
            originals.firstOrNull {
                val host = metadata(it).host.orEmpty()
                val domains =
                    listOf("bilivideo.com", "bilivideo.cn", "acgvideo.com", "acgvideo.cn", "edge.mountaintoys.cn") +
                        if (config.mode == CdnMode.Pinned) listOf("akamaized.net") else emptyList()
                !Regex("^(?:bvc|data|pbp|api\\w*)\\.").containsMatchIn(host) &&
                    !host.startsWith("mcdn.") &&
                    ".mcdn." !in host &&
                    domains.any { host == it || host.endsWith(".$it") }
            }
        val hosts =
            when (config.mode) {
                CdnMode.Mainland -> MAINLAND
                CdnMode.Overseas -> OVERSEAS
                CdnMode.Pinned -> listOf(config.pinnedHost)
            }
        val generated =
            donor
                ?.let { original ->
                    hosts.mapNotNull { host ->
                        // Replace only the authority: rebuilding a URL can normalize signed query bytes.
                        val parsed = ("https://$host").toHttpUrlOrNull() ?: return@mapNotNull null
                        if (parsed.encodedPath != "/" ||
                            parsed.query != null ||
                            parsed.username.isNotEmpty()
                        ) {
                            return@mapNotNull null
                        }
                        CdnUrls.override(original, host)
                    }
                }.orEmpty()
        return if (config.mode == CdnMode.Pinned) {
            originals.filter {
                runCatching {
                    URI(
                        it,
                    ).rawAuthority.equals(config.pinnedHost, true)
                }.getOrDefault(false)
            } +
                generated
        } else {
            val preferred =
                originals.filter {
                    (metadata(it).host in MAINLAND) ==
                        (config.mode == CdnMode.Mainland)
                }
            preferred + generated + originals
        }.distinct()
    }

    /**
     * Chooses and reserves ordinary work in one lock acquisition using current host loads.
     * Feasible idle routes take priority, but a busy feasible route beats an idle slow one.
     * If the deadline is unreachable, the globally fastest measured estimate wins.
     * Only demanded work may bypass an entirely cooling pool, with one recovery per session.
     */
    @Synchronized
    fun reserveOrdinary(
        track: MediaTrackSource,
        bytes: Long,
        deadlineNanos: Long?,
        excludedUrls: Collection<String>,
        allowRecovery: Boolean = false,
    ): RouteAssignment? = reserveRoute(track, bytes, deadlineNanos, excludedUrls, false, allowRecovery)

    /**
     * Selects and reserves a rescue atomically, excluding tried and cooling hosts.
     * Among routes that can meet the deadline, prefer idle hosts; otherwise choose the
     * globally fastest measured estimate. With no deadline, prefer measured idle hosts.
     */
    @Synchronized
    fun reserveRescue(
        track: MediaTrackSource,
        bytes: Long,
        deadlineNanos: Long?,
        excludedUrls: Collection<String>,
    ): RouteAssignment? = reserveRoute(track, bytes, deadlineNanos, excludedUrls, true)

    /** Checks rescue eligibility without reserving a route, drawing randomness, or recording selection. */
    @Synchronized
    fun hasRescueCandidate(
        track: MediaTrackSource,
        excludedUrls: Collection<String>,
    ): Boolean {
        val now = nowNanos()
        val excluded = excludedUrls.map(::routeKey).toSet()
        val urls = candidateUrls[track] ?: buildCandidateUrls(track)
        return urls.any { routeKey(it) !in excluded && (health[routeKey(it)]?.until ?: 0) <= now }
    }

    /** Checks low-buffer exploration without consuming its global spacing or host probe allowance. */
    @Synchronized
    fun hasRecoveryExplorationCandidate(
        track: MediaTrackSource,
        excludedUrls: Collection<String>,
    ): Boolean {
        if (config.mode == CdnMode.Pinned) return false
        val now = nowNanos()
        if (lastExploration?.let { now - it < 2_000_000_000L } == true) return false
        val excluded = excludedUrls.map(::routeKey).toSet()
        val urls = candidateUrls[track] ?: buildCandidateUrls(track)
        return urls.any { url ->
            routeKey(url) !in excluded &&
                assigned[routeKey(url)].isNullOrEmpty() &&
                (health[routeKey(url)]?.until ?: 0) <= now &&
                probedAt[mediaKey(url)]?.let { now - it >= 10_000_000_000L } != false &&
                explorationPriority(url, now) != null
        }
    }

    private fun reserveRoute(
        track: MediaTrackSource,
        bytes: Long,
        deadlineNanos: Long?,
        excludedUrls: Collection<String>,
        rescue: Boolean,
        allowRecovery: Boolean = false,
    ): RouteAssignment? {
        val now = nowNanos()
        val recovering = allCooling(track)
        if (recovering && (rescue || !allowRecovery || !recoveryAvailable())) return null
        val event = if (rescue) "cdn.rescue_selection" else "cdn.ordinary_selection"
        val excludedHosts = excludedUrls.map(::routeKey).toSet()
        val eligible =
            candidates(track, bytes)
                .filter { routeKey(it) !in excludedHosts }
                .filter { !rescue || (health[routeKey(it)]?.until ?: 0) <= now }
                .distinctBy(::routeKey)
        if (eligible.isEmpty()) {
            traceChoice(event, track, bytes, deadlineNanos, eligible, null, "no_alternative", now)
            return null
        }
        val estimates = eligible.associateWith { predictedFinishNanos(it, bytes) }
        val measured = eligible.filter { estimates[it] != null }.sortedBy { estimates[it] }

        fun idle(url: String): Boolean = assigned[routeKey(url)].isNullOrEmpty()
        val capable =
            if (deadlineNanos == null) {
                emptyList()
            } else {
                measured.filter {
                    requireNotNull(estimates[it]) <= deadlineNanos - now
                }
            }
        val capableIdle = capable.filter(::idle)
        val selected =
            when {
                capableIdle.isNotEmpty() -> capableIdle[random.nextInt(capableIdle.size)]
                capable.isNotEmpty() -> capable.first()
                measured.isNotEmpty() && deadlineNanos == null && rescue ->
                    measured.firstOrNull(::idle)
                        ?: measured.first()
                measured.isNotEmpty() -> measured.first()
                else -> eligible.firstOrNull(::idle) ?: eligible.first()
            }
        traceChoice(
            event,
            track,
            bytes,
            deadlineNanos,
            eligible,
            selected,
            when {
                capableIdle.isNotEmpty() -> "random_feasible_idle"
                capable.isNotEmpty() -> "fastest_feasible_busy"
                measured.isNotEmpty() && deadlineNanos != null && idle(selected) -> "fastest_idle_deadline_unreachable"
                measured.isNotEmpty() && deadlineNanos != null -> "fastest_busy_deadline_unreachable"
                measured.isNotEmpty() && idle(selected) -> "fastest_idle_no_deadline"
                measured.isNotEmpty() -> "fastest_busy_no_deadline"
                idle(selected) -> "unmeasured_idle"
                else -> "unmeasured_busy"
            },
            now,
            estimates,
        )
        // Selection and load reservation share this lock, including simultaneous ordinary starts.
        return requestStarted(selected, bytes).also {
            if (recovering) {
                outageRecovery = it
                traceChoice(event, track, bytes, deadlineNanos, eligible, selected, "demanded_cooldown_recovery", now)
            }
        }
    }

    /** Whether every route for this representation is temporarily suppressed. Empty pools are not outages. */
    @Synchronized
    fun allCooling(track: MediaTrackSource): Boolean {
        val urls = candidateUrls.getOrPut(track) { buildCandidateUrls(track) }
        val now = nowNanos()
        return urls.isNotEmpty() && urls.all { (health[routeKey(it)]?.until ?: 0) > now }
    }

    /** Recovery waiters hold no HTTP permits; cancellation releases the assignment via requestFinished. */
    @Synchronized
    fun recoveryAvailable(): Boolean = outageRecovery == null && nowNanos() >= recoveryNotBefore

    /** Distinguishes temporary recovery admission waits from exhausting this range's real alternatives. */
    @Synchronized
    fun waitingForRecovery(
        track: MediaTrackSource,
        excludedUrls: Collection<String>,
    ): Boolean = allCooling(track) && hasUntriedRoute(track, excludedUrls)

    /** Retry exhaustion is independent of cooldown admission and must never wait for a socket slot. */
    @Synchronized
    fun hasUntriedRoute(
        track: MediaTrackSource,
        excludedUrls: Collection<String>,
    ): Boolean {
        val excluded = excludedUrls.map(::routeKey).toSet()
        return candidateUrls.getOrPut(track) { buildCandidateUrls(track) }.any { routeKey(it) !in excluded }
    }

    /** Rechecks lightly sampled, stale, or recovering idle routes; reserve=false only checks eligibility. */
    @Synchronized
    fun explorationCandidate(
        track: MediaTrackSource,
        excludingUrl: String,
        reserve: Boolean = true,
    ): String? {
        if (config.mode == CdnMode.Pinned) return null
        val now = nowNanos()
        if (lastExploration?.let { now - it < 2_000_000_000L } == true) return null

        val alternate = chooseExploration(track, setOf(routeKey(excludingUrl)), now) ?: return null
        if (reserve) {
            lastExploration = now
            probedAt[mediaKey(alternate)] = now
            if (traceEnabled()) {
                trace(
                    "cdn.exploration_selection",
                    mapOf(
                        "track" to track.kind.name,
                        "host" to metadata(alternate).host,
                        "reason" to
                            when (explorationPriority(alternate, now)) {
                                0 -> "insufficient_meaningful_evidence"
                                1 -> "stale_evidence"
                                else -> "penalized_or_failed_recheck"
                            },
                        "candidate" to traceCandidate(alternate, 0, now, null),
                    ),
                )
            }
        }
        return alternate
    }

    /**
     * Reserves an unused exploratory route for a protected low-buffer duplicate atomically.
     * Unlike distant exploration this needs no playback slack; the caller retains the normal
     * request and owns all duplicate-slot and memory limits. Tried hosts are never repeated.
     */
    @Synchronized
    fun reserveRecoveryExploration(
        track: MediaTrackSource,
        bytes: Long,
        excludedUrls: Collection<String>,
    ): RouteAssignment? {
        if (config.mode == CdnMode.Pinned) return null
        val now = nowNanos()
        if (lastExploration?.let { now - it < 2_000_000_000L } == true) return null
        val alternate = chooseExploration(track, excludedUrls.map(::routeKey).toSet(), now) ?: return null
        lastExploration = now
        probedAt[mediaKey(alternate)] = now
        if (traceEnabled()) {
            trace(
                "cdn.exploration_selection",
                mapOf(
                    "track" to track.kind.name,
                    "host" to metadata(alternate).host,
                    "reason" to
                        if (explorationPriority(alternate, now) == 0) "low_buffer_unmeasured" else "low_buffer_retest",
                    "purpose" to "recovery_exploration_reservation",
                    "bytes" to bytes,
                    "candidate" to traceCandidate(alternate, bytes, now, null),
                ),
            )
        }
        return requestStarted(alternate, bytes)
    }

    private fun explorationPriority(
        url: String,
        now: Long,
    ): Int? {
        val measured = evidence[mediaKey(url)]?.meaningfulAt
        return when {
            measured == null -> 0
            now - measured >= 15_000_000_000L -> 1
            (schedulingConfidence(url) < 1.0 || (health[routeKey(url)]?.failures ?: 0) > 0) &&
                now - measured >= 10_000_000_000L -> 2
            else -> null
        }
    }

    // Both exploration modes share evidence and spacing, so switching modes cannot
    // immediately retest the same representation or bypass another track's active host.
    private fun chooseExploration(
        track: MediaTrackSource,
        excludedHosts: Set<String>,
        now: Long,
    ): String? =
        candidates(track)
            .filter { routeKey(it) !in excludedHosts }
            .filter { assigned[routeKey(it)].isNullOrEmpty() }
            .filter { (health[routeKey(it)]?.until ?: 0) <= now }
            .filter { probedAt[mediaKey(it)]?.let { at -> now - at >= 10_000_000_000L } != false }
            .mapNotNull { url -> explorationPriority(url, now)?.let { rank -> url to rank } }
            .minWithOrNull(
                compareBy<Pair<String, Int>> { it.second }
                    .thenBy { probedAt[mediaKey(it.first)] ?: Long.MIN_VALUE },
            )?.first

    // Active request counts influence idle/busy choice, never the finalized per-block rate.
    private fun predictedFinishNanos(
        url: String,
        bytes: Long,
    ): Double? {
        val speed = performance[mediaKey(url)]?.window?.estimate() ?: return null
        if (speed <= 0) return null
        return bytes.coerceAtLeast(0) / (speed * schedulingConfidence(url))
    }

    /** Reader-lifetime epoch, captured at open so queued old work cannot inherit a later seek epoch. */
    @Synchronized
    fun currentEvidenceEpoch(): Long = evidenceEpoch

    /** Keeps each CDN/representation estimate as fallback while isolating new seek-position evidence. */
    @Synchronized
    fun beginSeek(): Long {
        performance.values.forEach { it.window.beginSeek() }
        evidenceEpoch++
        return evidenceEpoch
    }

    /** Reserves byte load synchronously before dispatch, including requests waiting to start. */
    @Synchronized
    fun requestStarted(
        url: String,
        bytes: Long,
    ): RouteAssignment {
        // Idle/busy reservations remain separate from the measured per-block transfer forecast.
        val estimate = estimatedTransferNanos(url, bytes)
        val requests = assigned.getOrPut(routeKey(url)) { mutableSetOf() }
        return RouteAssignment(url, bytes.coerceAtLeast(0), requests.size + 1, estimate, evidenceEpoch).also {
            requests.add(it)
        }
    }

    /** Updates assigned-byte diagnostics without releasing the request or changing measured speed. */
    @Synchronized
    fun requestProgress(
        assignment: RouteAssignment,
        receivedBytes: Long,
    ) {
        assignment.receivedBytes = receivedBytes.coerceIn(assignment.receivedBytes, assignment.bytes)
    }

    /** Escalates repeated misses; a buffering upgrade applies once without counting another miss. */
    @Synchronized
    fun rescued(
        assignment: RouteAssignment,
        penalize: Boolean = true,
        buffering: Boolean = false,
    ) {
        if (assignment.rescued && !buffering) return
        assignment.rescued = true
        if (!penalize) {
            if (traceEnabled()) {
                trace(
                    "cdn.penalty",
                    mapOf(
                        "host" to metadata(assignment.url).host,
                        "reason" to "no_fair_deadline_opportunity",
                        "applied" to false,
                        "before" to schedulingConfidence(assignment.url),
                        "after" to schedulingConfidence(assignment.url),
                    ),
                )
            }
            return
        }
        val level = if (buffering) 2 else 1
        if (assignment.penaltyLevel >= level) return
        val key = mediaKey(assignment.url)
        val before = schedulingConfidence(assignment.url)
        val strikes = penaltyStrikes[key] ?: 0
        if (assignment.penaltyLevel == 0) penaltyStrikes[key] = (strikes + 1).coerceAtMost(6)
        confidence[key] =
            if (buffering) {
                (schedulingConfidence(assignment.url) * 0.1).coerceAtMost(0.02).coerceAtLeast(0.01)
            } else {
                (schedulingConfidence(assignment.url) * (0.75 - 0.1 * strikes).coerceAtLeast(0.25)).coerceAtLeast(0.01)
            }
        assignment.penaltyLevel = level
        if (traceEnabled()) {
            trace(
                "cdn.penalty",
                mapOf(
                    "host" to metadata(assignment.url).host,
                    "reason" to if (buffering) "culpable_buffering" else "deadline_rescue",
                    "applied" to true,
                    "before" to before,
                    "after" to schedulingConfidence(assignment.url),
                    "strikes" to penaltyStrikes[key],
                    "level" to level,
                ),
            )
        }
    }

    /** Representation-local scheduling discount; raw measured throughput remains unchanged. */
    @Synchronized
    fun schedulingConfidence(url: String): Double = confidence[mediaKey(url)] ?: 1.0

    /** Releases a completed, failed, or cancelled assignment without changing other requests. */
    @Synchronized
    fun requestFinished(assignment: RouteAssignment) {
        if (outageRecovery === assignment) outageRecovery = null
        val key = routeKey(assignment.url)
        assigned[key]?.let { requests ->
            requests.remove(assignment)
            if (requests.isEmpty()) assigned.remove(key)
        }
    }

    /** Forecast for remaining bytes using the same penalty-adjusted per-block speed. */
    @Synchronized
    fun remainingTransferNanos(
        url: String,
        bytes: Long,
    ): Long? = estimatedTransferNanos(url, bytes)

    /** Transfer time for this block only; response latency is already included in finalized samples. */
    @Synchronized
    fun estimatedTransferNanos(
        url: String,
        bytes: Long,
    ): Long? = predictedFinishNanos(url, bytes)?.coerceAtMost(Long.MAX_VALUE.toDouble())?.toLong()

    /** Updates throughput only after the entire range has passed validation. */
    @Synchronized
    fun success(
        url: String,
        bytes: Int,
        nanos: Long,
        concurrentRequests: Int = 1,
        latencyNanos: Long = 0,
        onTime: Boolean = false,
        startupProbe: Boolean = false,
        mediaTimeMs: Long? = null,
        evidenceEpoch: Long? = null,
    ) {
        val item = health.getOrPut(routeKey(url)) { Health() }
        val key = mediaKey(url)
        val now = nowNanos()
        if (onTime &&
            !startupProbe &&
            schedulingConfidence(url) < 1.0 &&
            lastRecovery[key]?.let { now - it >= 10_000_000_000L } != false
        ) {
            val before = schedulingConfidence(url)
            lastRecovery[key] = now
            confidence[key] = (schedulingConfidence(url) + 0.05).coerceAtMost(1.0)
            penaltyStrikes[key] = ((penaltyStrikes[key] ?: 0) - 1).coerceAtLeast(0)
            if (traceEnabled()) {
                trace(
                    "cdn.recovery",
                    mapOf(
                        "host" to metadata(url).host,
                        "reason" to "validated_on_time_completion",
                        "before" to before,
                        "after" to schedulingConfidence(url),
                        "strikes" to penaltyStrikes[key],
                    ),
                )
            }
        }
        if (!startupProbe && bytes > 0) {
            val sample = evidence.getOrPut(key) { Evidence() }
            if (now - sample.updatedAt >= 15_000_000_000L) {
                sample.bytes = 0
                sample.samples = 0
            }
            sample.bytes = (sample.bytes + bytes).coerceAtMost(256 * 1024L)
            sample.samples = (sample.samples + 1).coerceAtMost(3)
            sample.updatedAt = now
            if (sample.bytes >= 256 * 1024 || sample.samples >= 3 || nanos >= 1_000_000_000L) {
                sample.meaningfulAt = now
            }
        }
        // Health, penalties and exploration eligibility are independent of speed epochs.
        // A successful old request is still healthy; only its speed sample is obsolete after seek.
        if (evidenceEpoch == null || evidenceEpoch == this.evidenceEpoch) {
            val measured = performance.getOrPut(key) { Performance() }
            measured.window.add(bytes, nanos, if (startupProbe) null else mediaTimeMs)
            measured.measuredAt = now
            measured.concurrency = concurrentRequests.coerceAtLeast(1).toDouble()
            measured.latencyNanos = latencyNanos.coerceIn(0, nanos.coerceAtLeast(0)).toDouble()
        }
        if (outageRecovery?.url == url) recoveryNotBefore = 0
        item.failures = 0
        item.until = 0
    }

    /** Temporarily excludes failing routes, scoped to this playback session. */
    @Synchronized
    fun failure(url: String) {
        val item = health.getOrPut(routeKey(url)) { Health() }
        if (outageRecovery?.url == url) recoveryNotBefore = nowNanos() + 1_000_000_000L
        item.failures++
        item.until = nowNanos() + (3_000_000_000L shl item.failures.coerceAtMost(4))
    }

    /** Remaining suppression time for diagnostics, using the same monotonic clock as routing. */
    @Synchronized
    fun cooldownRemainingMs(url: String): Long =
        (((health[routeKey(url)]?.until ?: 0) - nowNanos()) / 1_000_000).coerceAtLeast(0)

    // Ranking calls also discover routes and recheck exploration eligibility. Throttle those
    // read-only calls, but retain every actual rescue decision and deadline-aware ranking.
    private fun traceChoice(
        event: String,
        track: MediaTrackSource,
        bytes: Long,
        deadline: Long?,
        urls: List<String>,
        selected: String?,
        reason: String,
        now: Long,
        estimates: Map<String, Double?>? = null,
    ) {
        if (!traceEnabled()) return
        if (event == "cdn.ranking" && deadline == null) {
            if (lastRankingTrace[track.kind]?.let { now - it < 1_000_000_000L } == true) return
            lastRankingTrace[track.kind] = now
        }
        val candidates =
            urls.map {
                traceCandidate(
                    it,
                    bytes,
                    now,
                    deadline,
                    if (estimates !=
                        null
                    ) {
                        estimates[it]
                    } else {
                        predictedFinishNanos(it, bytes)
                    },
                )
            }
        val remaining = deadline?.minus(now)
        trace(
            event,
            mapOf(
                "track" to track.kind.name,
                "purpose" to
                    when (event) {
                        "cdn.ranking" -> "ranking_not_dispatch"
                        "cdn.ordinary_selection" -> "ordinary_reservation"
                        else -> "rescue_reservation"
                    },
                "host" to selected?.let { metadata(it).host },
                "reason" to reason,
                "bytes" to bytes,
                "deadlineRemainingMs" to remaining?.div(1_000_000.0),
                "requiredBytesPerSecond" to remaining?.takeIf { it > 0 }?.let { bytes * 1_000_000_000.0 / it },
                "eligibleCount" to candidates.size,
                "measuredCount" to candidates.count { it["predictedMs"] != null },
                "idleCount" to candidates.count { it["activeRequests"] == 0 },
                "feasibleCount" to candidates.count { it["feasible"] == true },
                "candidates" to candidates,
            ),
        )
    }

    private fun traceCandidate(
        url: String,
        bytes: Long,
        now: Long,
        deadline: Long?,
        prediction: Double? = predictedFinishNanos(url, bytes),
    ): Map<String, Any?> {
        val measured = performance[mediaKey(url)]
        val requests = assigned[routeKey(url)]
        val age = measured?.let { (now - it.measuredAt).coerceAtLeast(0) }
        return mapOf(
            "host" to metadata(url).host,
            "rawBytesPerSecond" to measured?.window?.estimate()?.times(1_000_000_000.0),
            "speedModel" to "finalized_block_media_window",
            "evidenceEpoch" to evidenceEpoch,
            "furthestMediaTimeMs" to measured?.window?.furthestMediaTimeMs,
            "windowSamples" to measured?.window?.sampleCount,
            "newEvidenceWeight" to measured?.window?.newEvidenceWeight(),
            "confidence" to schedulingConfidence(url),
            "measurementAgeMs" to age?.div(1_000_000.0),
            "measuredConcurrency" to measured?.concurrency,
            "effectiveBlockBytesPerSecond" to
                measured?.window?.estimate()?.let { it * 1_000_000_000.0 * schedulingConfidence(url) },
            "latencyMs" to measured?.latencyNanos?.div(1_000_000.0),
            "activeRequests" to (requests?.size ?: 0),
            "remainingAssignedBytes" to (requests?.sumOf { (it.bytes - it.receivedBytes).coerceAtLeast(0) } ?: 0),
            "cooldownRemainingMs" to (((health[routeKey(url)]?.until ?: 0) - now).coerceAtLeast(0) / 1_000_000),
            "predictedMs" to prediction?.div(1_000_000.0),
            "feasible" to if (deadline != null && prediction != null) prediction <= deadline - now else null,
        )
    }

    private companion object {
        val MAINLAND =
            listOf(
                "upos-sz-mirrorali.bilivideo.com",
                "upos-sz-mirrorhw.bilivideo.com",
                "upos-sz-mirrorbos.bilivideo.com",
                "upos-sz-mirror08c.bilivideo.com",
                "upos-sz-mirrorbd.bilivideo.com",
                "upos-sz-mirror14b.bilivideo.com",
                "upos-sz-estgoss.bilivideo.com",
                "upos-sz-mirrorcos.bilivideo.com",
            )
        val OVERSEAS =
            listOf(
                "upos-sz-mirrorcosov.bilivideo.com",
                "upos-sz-mirroraliov.bilivideo.com",
                "cn-hk-eq-01-01.bilivideo.com",
                "cn-hk-eq-01-03.bilivideo.com",
            )
    }
}
