package dev.frost819.newbv.player.download

/** Builds independent range prefixes so a rescue can fetch only bytes still missing. */
internal object RescueStitch {
    /**
     * Freezes a previously retained prefix and the published part of an active response.
     *
     * [received] must be read after the downloader publishes its completed writes. The
     * active response may keep writing beyond that count; this copy never reads those bytes.
     */
    fun prefix(
        base: ByteArray,
        body: ByteArray,
        received: Int,
        totalLength: Int,
    ): ByteArray {
        require(totalLength >= 0)
        require(base.size <= totalLength)
        require(received in 0..body.size)
        require(received <= totalLength - base.size)
        return ByteArray(base.size + received).also { frozen ->
            base.copyInto(frozen)
            body.copyInto(frozen, destinationOffset = base.size, endIndex = received)
        }
    }

    /** Joins the exact suffix requested after this frozen prefix; partial or excess data is rejected. */
    fun join(
        prefix: ByteArray,
        suffix: ByteArray,
        totalLength: Int,
    ): ByteArray {
        require(totalLength >= 0)
        require(prefix.size <= totalLength)
        require(suffix.size == totalLength - prefix.size)
        return ByteArray(totalLength).also { result ->
            prefix.copyInto(result)
            suffix.copyInto(result, destinationOffset = prefix.size)
        }
    }

    /** Avoids issuing another request when at most 64 KiB remain in the current range. */
    fun shouldWait(remaining: Long): Boolean {
        require(remaining >= 0)
        return remaining <= 65536L
    }
}
