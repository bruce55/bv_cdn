package dev.frost819.newbv.app.util

import java.net.URI
import java.util.Locale

/** Validates CDN choices and rewrites only eligible VOD media authorities. */
object CdnOverride {
    /** Accepts a host, host:port, or HTTP(S) URL; invalid input returns an empty string. */
    fun normalizeHost(rawHost: String): String {
        val input = rawHost.trim()
        if (input.isEmpty() || input.any { it.isWhitespace() }) return ""
        val url =
            if (input.startsWith("//")) {
                "https:$input"
            } else if ("://" in input) {
                input
            } else {
                "https://$input"
            }
        val uri = runCatching { URI(url) }.getOrNull() ?: return ""
        if (uri.scheme.lowercase(Locale.ROOT) !in listOf("http", "https")) return ""
        if (uri.rawUserInfo != null || uri.host.isNullOrBlank()) return ""
        if (uri.port != -1 && uri.port !in 1..65535) return ""
        return uri.rawAuthority.lowercase(Locale.ROOT)
    }

    /** Preserves the signed path/query verbatim; blank/invalid choices and non-media hosts are unchanged. */
    fun apply(
        url: String,
        overrideHost: String,
    ): String {
        val authority = normalizeHost(overrideHost)
        if (authority.isEmpty()) return url
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        if (uri.scheme?.lowercase(Locale.ROOT) !in listOf("http", "https")) return url
        if (uri.rawUserInfo != null) return url
        val host = uri.host?.lowercase(Locale.ROOT) ?: return url
        if (Regex("^(?:bvc|data|pbp|api\\w*)\\.").containsMatchIn(host)) return url
        if (host.startsWith("mcdn.") || ".mcdn." in host) return url
        val domains =
            listOf(
                "bilivideo.com",
                "bilivideo.cn",
                "acgvideo.com",
                "acgvideo.cn",
                "edge.mountaintoys.cn",
                "akamaized.net",
            )
        if (domains.none { host == it || host.endsWith(".$it") }) return url
        // Do not decode/re-encode signed query values or percent-escaped paths.
        val start = url.indexOf("://") + 3
        return url.substring(0, start) + authority + url.substring(start + uri.rawAuthority.length)
    }
}
