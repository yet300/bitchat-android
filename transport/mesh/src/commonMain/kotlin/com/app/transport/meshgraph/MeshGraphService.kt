package com.app.transport.meshgraph

import co.touchlab.stately.collections.ConcurrentMutableMap
import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import com.app.common.AppDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Maintains an internal graph of the mesh based on gossip.
 * Nodes are peers (peerID), edges are direct connections.
 */
class MeshGraphService(
    dispatchers: AppDispatchers = AppDispatchers(),
) {
    data class GraphNode(val peerID: String, val nickname: String?)
    data class GraphEdge(val a: String, val b: String, val isConfirmed: Boolean, val confirmedBy: String? = null)
    data class GraphSnapshot(val nodes: List<GraphNode>, val edges: List<GraphEdge>)

    companion object {
        // Coalesce snapshot rebuilds: at 1000 peers × announce/30s a per-announce rebuild is ~33
        // full O(N·K + N log N) rebuilds + emissions per second on the packet path. Instead each
        // verified announce only marks the graph dirty; a timer republishes at most this often.
        private const val PUBLISH_INTERVAL_MS = 1_500L
        // The debug topology view does not need to hold 10^4 nodes; cap it to bound rebuild cost
        // and the emitted snapshot. Kept are the most-recently-active origins.
        private const val MAX_GRAPH_NODES = 500
    }

    // Guards graph mutation and the dirty flag, and the consistent map copy taken by
    // publishSnapshot. Non-suspending, bounded work only — the heavy rebuild runs off this lock.
    private val lock = Lock()
    private var dirty = false
    // Map peerID -> nickname (may be null if unknown)
    private val nicknames = ConcurrentMutableMap<String, String?>()
    // Announcements: peerID -> set of neighbor peerIDs that *this* peer claims to see
    private val announcements = ConcurrentMutableMap<String, Set<String>>()
    // Latest announcement timestamp per peer (ULong from packet)
    private val lastUpdate = ConcurrentMutableMap<String, ULong>()

    private val _graphState = MutableStateFlow(GraphSnapshot(emptyList(), emptyList()))
    val graphState: StateFlow<GraphSnapshot> = _graphState.asStateFlow()

    private val scope = CoroutineScope(dispatchers.default + SupervisorJob())

    init {
        scope.launch {
            while (isActive) {
                delay(PUBLISH_INTERVAL_MS)
                if (takeDirty()) publishSnapshot()
            }
        }
    }

    private fun takeDirty(): Boolean = lock.withLock {
        val d = dirty
        dirty = false
        d
    }

    /**
     * Update graph from a verified announcement.
     * Replaces previous neighbors for origin if this is newer (by timestamp).
     */
    fun updateFromAnnouncement(originPeerID: String, originNickname: String?, neighborsOrNull: List<String>?, timestamp: ULong) {
        lock.withLock {
            // Always update nickname if provided
            if (originNickname != null) nicknames[originPeerID] = originNickname

            // 1. Check timestamp first to ensure this is the latest word from the peer
            val prevTs = lastUpdate[originPeerID]
            if (prevTs != null && prevTs >= timestamp) {
                // Older or equal update: ignore
                return
            }
            lastUpdate[originPeerID] = timestamp

            // 2. Latest announcement determines state.
            // If neighborsOrNull is null (TLV omitted), it means the peer is not reporting any neighbors (empty list).
            val neighbors = neighborsOrNull ?: emptyList()
            
            // Filter out self-loops just in case
            val newSet = neighbors.distinct().take(10).filter { it != originPeerID }.toSet()
            announcements[originPeerID] = newSet

            dirty = true
        }
    }

    fun updateNickname(peerID: String, nickname: String?) {
        if (nickname == null) return
        lock.withLock {
            nicknames[peerID] = nickname
            dirty = true
        }
    }

    /**
     * Remove a peer from the graph completely (e.g. when stale/offline).
     */
    fun removePeer(peerID: String) {
        lock.withLock {
            nicknames.remove(peerID)
            announcements.remove(peerID)
            lastUpdate.remove(peerID)
            dirty = true
        }
    }

    private fun publishSnapshot() {
        // Take a consistent cross-map copy under the lock, then do the O(N·K + N log N) rebuild +
        // sort OUTSIDE the lock so it never runs on the packet path.
        val (nicknamesCopy, announcementsCopy, lastUpdateCopy) = lock.withLock {
            Triple(nicknames.toMap(), announcements.toMap(), lastUpdate.toMap())
        }

        // Bound the graph to the most-recently-active origins (caps rebuild cost and node count).
        val activeOrigins = announcementsCopy.keys
            .sortedByDescending { lastUpdateCopy[it] ?: 0uL }
            .take(MAX_GRAPH_NODES)
            .toSet()

        val allNodes = LinkedHashSet<String>()
        activeOrigins.forEach { origin ->
            allNodes.add(origin)
            announcementsCopy[origin]?.forEach { allNodes.add(it) }
        }
        // Include known nicknames as isolated nodes too (parity with prior behavior), up to the cap.
        for (peer in nicknamesCopy.keys) {
            if (allNodes.size >= MAX_GRAPH_NODES) break
            allNodes.add(peer)
        }
        val cappedNodes = if (allNodes.size > MAX_GRAPH_NODES) {
            allNodes.asSequence().take(MAX_GRAPH_NODES).toSet()
        } else {
            allNodes
        }

        val nodeList = cappedNodes.map { GraphNode(it, nicknamesCopy[it]) }.sortedBy { it.peerID }

        val edges = mutableListOf<GraphEdge>()
        val processedPairs = mutableSetOf<Pair<String, String>>()

        // We only care about connections that exist in at least one direction; iterating the
        // (capped) active origins covers every declared edge among included nodes.
        activeOrigins.forEach { source ->
            val targets = announcementsCopy[source] ?: return@forEach
            targets.forEach { target ->
                if (source !in cappedNodes || target !in cappedNodes) return@forEach
                val pair = if (source <= target) source to target else target to source
                if (processedPairs.add(pair)) {
                    val (a, b) = pair
                    val aAnnouncesB = announcementsCopy[a]?.contains(b) == true
                    val bAnnouncesA = announcementsCopy[b]?.contains(a) == true

                    if (aAnnouncesB && bAnnouncesA) {
                        edges.add(GraphEdge(a, b, isConfirmed = true))
                    } else if (aAnnouncesB) {
                        edges.add(GraphEdge(a, b, isConfirmed = false, confirmedBy = a))
                    } else if (bAnnouncesA) {
                        edges.add(GraphEdge(a, b, isConfirmed = false, confirmedBy = b))
                    }
                }
            }
        }

        val sortedEdges = edges.sortedWith(compareBy({ it.a }, { it.b }))
        _graphState.value = GraphSnapshot(nodeList, sortedEdges)
    }

}
