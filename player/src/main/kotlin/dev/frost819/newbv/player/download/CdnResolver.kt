package dev.frost819.newbv.player.download

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URI

/** Session-local route policy; signed paths and queries are retained verbatim. */
internal class CdnResolver(
    private val config: ParallelDownloadConfig,
) {
    private data class Health(
        var failures: Int = 0,
        var until: Long = 0,
        var speed: Double = 0.0,
    )

    private val health = mutableMapOf<String, Health>()
    private var cursor = 0

    /** Returns eligible URLs, preferring healthy routes while probing alternatives. */
    @Synchronized
    fun candidates(track: MediaTrackSource): List<String> {
        val originals = track.urls.filter { it.toHttpUrlOrNull() != null }.distinct()
        val donor =
            originals.firstOrNull {
                val host = it.toHttpUrlOrNull()?.host.orEmpty()
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
                        val authority = runCatching { URI(original).rawAuthority }.getOrNull() ?: return@mapNotNull null
                        val start = original.indexOf("://") + 3
                        original.substring(0, start) + host + original.substring(start + authority.length)
                    }
                }.orEmpty()
        val eligible =
            if (config.mode == CdnMode.Pinned) {
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
                        (it.toHttpUrlOrNull()?.host in MAINLAND) ==
                            (config.mode == CdnMode.Mainland)
                    }
                preferred + generated + originals
            }.distinct()
        val now = System.nanoTime()
        val ready = eligible.filter { (health[it]?.until ?: 0) <= now }.ifEmpty { eligible }
        if (ready.isEmpty()) return emptyList()
        val offset = cursor++ % ready.size
        val rotated = ready.drop(offset) + ready.take(offset)
        // Periodic exploration prevents one early measurement from fixing the route forever.
        return if (cursor % 4 == 0) rotated else rotated.sortedByDescending { health[it]?.speed ?: 0.0 }
    }

    /** Updates throughput only after the entire range has passed validation. */
    @Synchronized
    fun success(
        url: String,
        bytes: Int,
        nanos: Long,
    ) {
        val item = health.getOrPut(url) { Health() }
        val speed = bytes.toDouble() / nanos.coerceAtLeast(1)
        item.speed = if (item.speed == 0.0) speed else item.speed * 0.65 + speed * 0.35
        item.failures = 0
        item.until = 0
    }

    /** Temporarily excludes failing routes, scoped to this playback session. */
    @Synchronized
    fun failure(url: String) {
        val item = health.getOrPut(url) { Health() }
        item.failures++
        item.until = System.nanoTime() + (3_000_000_000L shl item.failures.coerceAtMost(4))
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
