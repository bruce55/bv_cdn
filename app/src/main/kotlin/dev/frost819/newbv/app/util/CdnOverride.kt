package dev.frost819.newbv.app.util

import dev.frost819.newbv.player.CdnUrls

/** App-facing compatibility facade for shared signed media URL handling. */
object CdnOverride {
    /** Accepts a host, host:port, or HTTP(S) URL; invalid input returns an empty string. */
    fun normalizeHost(rawHost: String): String = CdnUrls.normalizeHost(rawHost)

    /** Rewrites only eligible media authorities, retaining signed paths and queries verbatim. */
    fun apply(
        url: String,
        overrideHost: String,
    ): String = CdnUrls.override(url, overrideHost)
}
