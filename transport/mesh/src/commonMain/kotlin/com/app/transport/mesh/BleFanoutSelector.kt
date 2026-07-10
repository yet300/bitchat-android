package com.app.transport.mesh

import com.app.transport.crypto.Sha256
import com.app.transport.protocol.MessageType

/**
 * Deterministic broadcast fanout subset — port of the reference iOS
 * `Services/BLE/BLEFanoutSelector.swift` subset policy.
 *
 * Non-exempt broadcasts go to a deterministic per-message subset of ~log2(count)+1
 * links instead of every neighbor: the subset is chosen by sorting links on
 * SHA256(messageID + "::" + linkID), so different messages spread load across
 * different links while every copy of one message picks the same links.
 * Fragment / announce / requestSync are exempt and go to ALL links (announces bind
 * links to peers; fragments must not lose pieces; requestSync is link-local).
 */
internal object BleFanoutSelector {

    fun shouldSubset(packetType: UByte): Boolean =
        packetType != MessageType.FRAGMENT.value &&
            packetType != MessageType.ANNOUNCE.value &&
            packetType != MessageType.REQUEST_SYNC.value

    /** min(count, floor(log2(count-1)) + 2) for count > 2; e.g. 4->3, 8->4, 16->5, 100->8. */
    fun subsetSize(count: Int): Int {
        if (count <= 0) return 0
        if (count <= 2) return count
        var value = count - 1
        var bits = 0
        while (value > 0) {
            value = value shr 1
            bits += 1
        }
        return minOf(count, maxOf(1, bits + 1))
    }

    /**
     * Deterministic subset of [k] link ids: sort by SHA256(seed + "::" + linkId)
     * (unsigned lexicographic), ties by linkId, take the first k.
     */
    fun selectSubset(linkIds: List<String>, k: Int, seed: String): Set<String> {
        if (k <= 0) return linkIds.toSet()
        if (linkIds.size <= k) return linkIds.toSet()
        return linkIds
            .map { id -> Sha256.digest((seed + "::" + id).encodeToByteArray()) to id }
            .sortedWith(
                compareBy<Pair<ByteArray, String>> { scoreKey(it.first) }.thenBy { it.second }
            )
            .take(k)
            .map { it.second }
            .toSet()
    }

    // Unsigned lexicographic byte order via a hex projection (stable, allocation-cheap
    // for 32-byte digests; matches iOS's per-byte unsigned comparison).
    private fun scoreKey(digest: ByteArray): String {
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            if (v < 16) sb.append('0')
            sb.append(v.toString(16))
        }
        return sb.toString()
    }
}
