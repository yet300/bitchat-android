package com.app.transport.meshgraph

import com.app.common.utils.Log

/**
 * Computes shortest paths on the current mesh graph snapshot using Dijkstra.
 * Assumes unit edge weights.
 */
internal object RoutePlanner {
    private const val TAG = "RoutePlanner"

    /**
     * Return full path [src, ..., dst] if reachable, else null.
     */
    fun shortestPath(src: String, dst: String, meshGraphService: MeshGraphService): List<String>? {
        if (src == dst) return listOf(src)
        val snapshot = meshGraphService.graphState.value
        val neighbors = mutableMapOf<String, MutableSet<String>>()
        
        // Only consider confirmed edges for routing
        snapshot.edges.filter { it.isConfirmed }.forEach { e ->
            neighbors.getOrPut(e.a) { mutableSetOf() }.add(e.b)
            neighbors.getOrPut(e.b) { mutableSetOf() }.add(e.a)
        }
        // Ensure nodes known even if isolated
        snapshot.nodes.forEach { n -> neighbors.getOrPut(n.peerID) { mutableSetOf() } }

        if (!neighbors.containsKey(src) || !neighbors.containsKey(dst)) return null

        val dist = mutableMapOf<String, Int>()
        val prev = mutableMapOf<String, String?>()
        val pq = BinaryMinHeap<Pair<String, Int>>(compareBy { it.second })

        neighbors.keys.forEach { v ->
            dist[v] = if (v == src) 0 else Int.MAX_VALUE
            prev[v] = null
        }
        pq.add(src to 0)

        while (pq.isNotEmpty()) {
            val top = pq.poll() ?: break
            val (u, d) = top
            if (d > (dist[u] ?: Int.MAX_VALUE)) continue
            if (u == dst) break
            neighbors[u]?.forEach { v ->
                val alt = d + 1
                if (alt < (dist[v] ?: Int.MAX_VALUE)) {
                    dist[v] = alt
                    prev[v] = u
                    pq.add(v to alt)
                }
            }
        }

        if ((dist[dst] ?: Int.MAX_VALUE) == Int.MAX_VALUE) return null

        val path = mutableListOf<String>()
        var cur: String? = dst
        while (cur != null) {
            path.add(cur)
            cur = prev[cur]
        }
        path.reverse()
        Log.d(TAG, "Computed path $path")
        return path
    }
}

/**
 * Minimal binary min-heap used by Dijkstra in [RoutePlanner]. Replaces
 * java.util.PriorityQueue, which is JVM-only, so the planner can live in commonMain.
 * Not thread-safe; the planner uses it single-threaded.
 */
private class BinaryMinHeap<T>(private val comparator: Comparator<T>) {
    private val heap = ArrayList<T>()

    fun isNotEmpty(): Boolean = heap.isNotEmpty()

    fun add(element: T) {
        heap.add(element)
        siftUp(heap.size - 1)
    }

    /** Remove and return the smallest element, or null if empty. */
    fun poll(): T? {
        if (heap.isEmpty()) return null
        val min = heap[0]
        val last = heap.removeAt(heap.size - 1)
        if (heap.isNotEmpty()) {
            heap[0] = last
            siftDown(0)
        }
        return min
    }

    private fun siftUp(start: Int) {
        var i = start
        while (i > 0) {
            val parent = (i - 1) / 2
            if (comparator.compare(heap[i], heap[parent]) >= 0) break
            swap(i, parent)
            i = parent
        }
    }

    private fun siftDown(start: Int) {
        var i = start
        val size = heap.size
        while (true) {
            val left = 2 * i + 1
            val right = 2 * i + 2
            var smallest = i
            if (left < size && comparator.compare(heap[left], heap[smallest]) < 0) smallest = left
            if (right < size && comparator.compare(heap[right], heap[smallest]) < 0) smallest = right
            if (smallest == i) break
            swap(i, smallest)
            i = smallest
        }
    }

    private fun swap(a: Int, b: Int) {
        val tmp = heap[a]
        heap[a] = heap[b]
        heap[b] = tmp
    }
}
