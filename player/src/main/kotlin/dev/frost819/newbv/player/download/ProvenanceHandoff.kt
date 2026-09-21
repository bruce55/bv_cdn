package dev.frost819.newbv.player.download

/** Transfers a finished result to its coordinator, or disposes a result arriving after cancellation. */
internal class ProvenanceHandoff {
    private var handle: ExactTransferLedger.Handle? = null
    private var handedOff = false
    private var discarded = false

    @Synchronized
    fun publish(value: ExactTransferLedger.Handle) {
        check(handle == null && !handedOff)
        if (discarded) value.close() else handle = value
    }

    @Synchronized
    fun take() {
        check(!discarded)
        handedOff = true
        handle = null
    }

    @Synchronized
    fun discard() {
        if (handedOff) return
        discarded = true
        handle?.close()
        handle = null
    }
}
