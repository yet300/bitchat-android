package com.app.transport.mesh

/** One relay scheduling choice: whether to relay and with what outgoing TTL. */
internal data class RelayDecision(
    val shouldRelay: Boolean,
    val newTTL: UByte,
    val delayMs: Int = 0,
)

/**
 * Centralized flood-control policy for relays — port of the reference iOS
 * `Services/RelayController.swift` decide() (broadcast branch included). Replaces the
 * legacy `ttl>=4 always relay` + total-network-size probabilistic drop, which damped
 * nothing on the first hops (7,6,5,4 always relayed) and keyed the probability on the
 * multi-hop peer count instead of the LOCAL link degree.
 *
 * The jitter-delay table and scheduled-relay-with-duplicate-cancel are a scoped
 * follow-up iteration (SYNC_SCALE_RESEARCH Q2); this port lands the TTL policy.
 */
internal object RelayController {
    /** iOS TransportConfig.messageTTLDefault. */
    private const val TTL_MAX = 7

    /** iOS highDegreeThreshold: at >=6 direct links the graph counts as dense. */
    const val HIGH_DEGREE_THRESHOLD = 6

    /** iOS bleFragmentRelayTtlCap / bleFragmentRelayTtlCapDense. */
    private const val FRAGMENT_TTL_CAP = 7
    private const val FRAGMENT_TTL_CAP_DENSE = 5

    fun decide(
        ttl: UByte,
        senderIsSelf: Boolean,
        recipientIsSelf: Boolean,
        isDirectedEncrypted: Boolean,
        isFragment: Boolean,
        isVoiceFrame: Boolean,
        isDirectedFragment: Boolean,
        isHandshake: Boolean,
        isAnnounce: Boolean,
        isRequestSync: Boolean,
        /** LOCAL degree: directly connected links, NOT the multi-hop peer count. */
        degree: Int,
        jitter: (IntRange) -> Int = { range -> range.random() },
    ): RelayDecision {
        val ttlCap = minOf(ttl.toInt(), TTL_MAX)

        // REQUEST_SYNC is link-local: never relay it, even when a peer crafts one
        // with TTL headroom to turn every reachable node into a responder.
        if (isRequestSync) return RelayDecision(false, ttlCap.toUByte())

        // Suppress obvious non-relays.
        if (ttlCap <= 1 || senderIsSelf || recipientIsSelf) {
            return RelayDecision(false, ttlCap.toUByte())
        }

        // Session-critical or directed traffic: deterministic, no TTL cap beyond 7.
        if (isHandshake || isDirectedFragment || isDirectedEncrypted) {
            return RelayDecision(true, (ttlCap - 1).toUByte(), jitter(if (isHandshake) 10..35 else 20..60))
        }

        // Live voice uses the fragment flood policy: sustained audio needs the same dense-graph
        // clamp, while sparse paths retain full TTL depth.
        if (isFragment || isVoiceFrame) {
            val cap = if (degree >= HIGH_DEGREE_THRESHOLD) FRAGMENT_TTL_CAP_DENSE else FRAGMENT_TTL_CAP
            val ttlLimit = minOf(ttlCap, cap)
            if (ttlLimit <= 1) return RelayDecision(false, ttlLimit.toUByte())
            return RelayDecision(true, (ttlLimit - 1).toUByte(), jitter(8..25))
        }

        // Broadcast TTL clamp by local degree:
        // - dense (>=6): shave to <=5 but keep multi-hop bridging (>=2)
        // - thin chain (<=2): every hop counts, flood cost minimal -> full depth
        // - else: clamp to 6 (7 for announces, which get extra headroom)
        val ttlLimit = when {
            degree >= HIGH_DEGREE_THRESHOLD -> maxOf(2, minOf(ttlCap, 5))
            degree <= 2 -> ttlCap
            else -> maxOf(2, minOf(ttlCap, if (isAnnounce) 7 else 6))
        }
        val delayRange = when (degree) {
            in 0..2 -> 10..40
            in 3..5 -> 60..150
            in 6..9 -> 80..180
            else -> 100..220
        }
        return RelayDecision(true, (ttlLimit - 1).toUByte(), jitter(delayRange))
    }
}
