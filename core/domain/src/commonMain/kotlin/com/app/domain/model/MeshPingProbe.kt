package com.app.domain.model

/**
 * Result of one directed echo probe (mesh ping/pong diagnostics).
 *
 * [hops] counts the links the reply crossed — 1 for a directly connected peer — and is null when
 * the probed peer reported TTLs the local node cannot reconcile.
 */
data class MeshPingProbe(
    val rttMs: Long,
    val hops: Int?,
)
