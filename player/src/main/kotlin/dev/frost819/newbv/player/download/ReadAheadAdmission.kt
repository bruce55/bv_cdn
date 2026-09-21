package dev.frost819.newbv.player.download

/** Central track ordering; the shared byte budget owns admission capacity. */
internal object ReadAheadAdmission {
    data class Candidate(
        val key: Long,
        val preparedTimeMs: Long?,
        val readTimeMs: Long?,
        val nextTimeMs: Long?,
        val pendingBlocks: Int,
        val urgent: Boolean,
    )

    // A denied lagging track must remain a balancing barrier. Removing it from the
    // candidate list lets cheap far-future audio repeatedly consume each small free gap.
    // Immediate reader demand may bypass the barrier; speculative work may only catch up.
    fun choose(
        candidates: List<Candidate>,
        blockedKeys: Set<Long> = emptySet(),
    ): Candidate? {
        val order =
            compareBy<Candidate> { !it.urgent }
                .thenBy { it.nextTimeMs ?: it.preparedTimeMs ?: Long.MIN_VALUE }
                .thenBy { it.preparedTimeMs ?: Long.MIN_VALUE }
                .thenBy { it.pendingBlocks }
                .thenBy { it.key }
        val preferred = candidates.minWithOrNull(order) ?: return null
        if (preferred.key !in blockedKeys) return preferred
        return candidates
            .filter { candidate ->
                candidate.key !in blockedKeys &&
                    (
                        candidate.urgent ||
                            candidate.preparedTimeMs != null &&
                            preferred.preparedTimeMs != null &&
                            candidate.preparedTimeMs <= preferred.preparedTimeMs
                    )
            }.minWithOrNull(order)
    }
}
