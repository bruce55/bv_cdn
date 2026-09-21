package dev.frost819.newbv.player.download

/** One transport gate for all HTTP attempts; priority never preempts a running request. */
internal class HttpAdmissionController(
    private val limit: Int,
) {
    enum class Priority { Recovery, Demand, Ordinary, Speculative }

    data class Order(
        val priority: Priority,
        val deadline: Long = Long.MAX_VALUE,
        val mediaTime: Long = Long.MAX_VALUE,
    )

    class Ticket internal constructor(
        internal val sequence: Long,
        internal val order: () -> Order,
        internal val eligible: () -> Boolean,
    )

    inner class Lease internal constructor(
        private val ordinary: Boolean,
    ) : AutoCloseable {
        private var closed = false

        override fun close() =
            synchronized(this@HttpAdmissionController) {
                if (!closed) {
                    closed = true
                    active--
                    if (ordinary) ordinaryActive--
                }
            }
    }

    private val waiting = linkedSetOf<Ticket>()
    private var sequence = 0L
    private var active = 0
    private var ordinaryActive = 0
    val freeSlots: Int @Synchronized get() = limit - active
    val freeOrdinarySlots: Int @Synchronized get() = (limit - 1 - ordinaryActive).coerceAtLeast(0)

    @Synchronized
    fun register(
        order: () -> Order,
        eligible: () -> Boolean = { true },
    ): Ticket = Ticket(++sequence, order, eligible).also(waiting::add)

    @Synchronized
    fun cancel(ticket: Ticket) {
        waiting.remove(ticket)
    }

    /** Readiness callbacks only inspect state; byte reservation happens in [reserve] for the winner. */
    @Synchronized
    fun canDispatch(
        ticket: Ticket,
        ignoreEligibility: Boolean = false,
    ): Boolean {
        if (ticket !in waiting || active >= limit || (!ignoreEligibility && !ticket.eligible())) return false
        val orders =
            waiting
                .filter {
                    (ignoreEligibility && it === ticket) || it.eligible()
                }.associateWith { it.order() }
        val eligible = orders.filterValues { it.priority == Priority.Recovery || ordinaryActive < limit - 1 }
        return eligible.keys.minWithOrNull(
            compareBy<Ticket> { eligible.getValue(it).priority.ordinal }
                .thenBy { eligible.getValue(it).deadline }
                .thenBy { eligible.getValue(it).mediaTime }
                .thenBy { it.sequence },
        ) === ticket
    }

    /** Atomically grants bytes and a slot; unsuccessful admission owns neither. */
    @Synchronized
    fun tryAcquire(
        ticket: Ticket,
        reserve: () -> Boolean = { true },
    ): Lease? {
        if (!canDispatch(ticket) || !reserve()) return null
        val ordinary = ticket.order().priority != Priority.Recovery
        waiting.remove(ticket)
        active++
        if (ordinary) ordinaryActive++
        return Lease(ordinary)
    }
}
