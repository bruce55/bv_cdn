package dev.frost819.newbv.player.download

/**
 * Exactly-once disposition of one HTTP body's bytes, independent of socket and block lifetimes.
 * A loser may finish after its coordinator has returned. Prefixes copied into another attempt
 * are excluded conservatively: if subsequently discarded, they remain unattributed overhead.
 */
internal class AttemptAccounting(
    private val report: (DownloadOverheadKind, Long) -> Unit,
) {
    private enum class Disposition { Pending, Retained, Discarded }

    private var disposition = Disposition.Pending
    private var finalBytes: Long? = null
    private var reason = DownloadOverheadKind.Other
    private var copiedPrefixBytes = 0L
    private var reported = false

    @Synchronized
    fun preservePrefix(bytes: Long) {
        check(!reported) { "Cannot preserve an already discarded body" }
        copiedPrefixBytes = maxOf(copiedPrefixBytes, bytes.coerceAtLeast(0))
    }

    @Synchronized
    fun retain() {
        if (disposition == Disposition.Pending) disposition = Disposition.Retained
    }

    @Synchronized
    fun discard() {
        if (disposition == Disposition.Pending) disposition = Disposition.Discarded
        reportIfFinal()
    }

    @Synchronized
    fun finish(
        bytes: Long,
        kind: DownloadOverheadKind,
    ) {
        if (finalBytes != null) return
        finalBytes = bytes.coerceAtLeast(0)
        reason = kind
        reportIfFinal()
    }

    private fun reportIfFinal() {
        val bytes = finalBytes ?: return
        if (reported || disposition != Disposition.Discarded) return
        reported = true
        val discarded = (bytes - copiedPrefixBytes).coerceAtLeast(0)
        if (discarded > 0) report(reason, discarded)
    }
}
