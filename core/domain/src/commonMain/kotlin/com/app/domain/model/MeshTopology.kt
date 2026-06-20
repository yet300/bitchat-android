package com.app.domain.model

/**
 * Mesh neighbour graph (P24) derived from gossip announcements: who can hear whom. An [edge] is
 * confirmed when both endpoints announce each other (vs. a single-sided claim).
 */
data class MeshTopology(
    val nodes: List<MeshNode>,
    val edges: List<MeshEdge>,
)

data class MeshNode(
    val peerId: String,
    val nickname: String?,
)

data class MeshEdge(
    val a: String,
    val b: String,
    val confirmed: Boolean,
)
