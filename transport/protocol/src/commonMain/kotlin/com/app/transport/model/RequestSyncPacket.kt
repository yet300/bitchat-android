package com.app.transport.model

/**
 * REQUEST_SYNC payload using GCS (Golomb-Coded Set) parameters.
 * TLV (type, length16, value), types:
 *  - 0x01: P (uint8) — Golomb-Rice parameter
 *  - 0x02: M (uint32, big-endian) — hash range (N * 2^P)
 *  - 0x03: data (opaque) — GR bitstream bytes
 *  - 0x04: types ([SyncTypeFlags], LE u64 trailing-zero-trimmed, 1-8 bytes) — OPTIONAL
 *  - 0x05: sinceTimestamp (uint64, big-endian) — OPTIONAL filter coverage cursor
 * Optional TLVs are appended AFTER 0x03, in ascending type order (iOS encode order).
 * With both optionals null the encoding is byte-identical to the historical 3-TLV
 * form — the frozen golden vectors stay unchanged.
 */
data class RequestSyncPacket(
    val p: Int,
    val m: Long,
    val data: ByteArray,
    val types: SyncTypeFlags? = null,
    val sinceTimestamp: Long? = null,
) {
    fun encode(): ByteArray {
        val out = ArrayList<Byte>()
        fun putTLV(t: Int, v: ByteArray) {
            out.add(t.toByte())
            val len = v.size
            out.add(((len ushr 8) and 0xFF).toByte())
            out.add((len and 0xFF).toByte())
            out.addAll(v.toList())
        }
        // P
        putTLV(0x01, byteArrayOf(p.toByte()))
        // M (uint32)
        val m32 = m.coerceAtMost(0xffff_ffffL)
        putTLV(
            0x02,
            byteArrayOf(
                ((m32 ushr 24) and 0xFF).toByte(),
                ((m32 ushr 16) and 0xFF).toByte(),
                ((m32 ushr 8) and 0xFF).toByte(),
                (m32 and 0xFF).toByte()
            )
        )
        // data
        putTLV(0x03, data)
        // Optional TLVs, appended after 0x03 in this order (matches iOS encode()).
        types?.toData()?.let { putTLV(0x04, it) }
        sinceTimestamp?.let { ts ->
            putTLV(
                0x05,
                byteArrayOf(
                    ((ts ushr 56) and 0xFF).toByte(),
                    ((ts ushr 48) and 0xFF).toByte(),
                    ((ts ushr 40) and 0xFF).toByte(),
                    ((ts ushr 32) and 0xFF).toByte(),
                    ((ts ushr 24) and 0xFF).toByte(),
                    ((ts ushr 16) and 0xFF).toByte(),
                    ((ts ushr 8) and 0xFF).toByte(),
                    (ts and 0xFF).toByte()
                )
            )
        }
        return out.toByteArray()
    }

    companion object {
        // Receiver-side hard cap to avoid DoS — wire-level limit, kept here as the
        // source of truth for the sync packet (sync layer mirrors it in SyncDefaults).
        const val MAX_ACCEPT_FILTER_BYTES: Int = 1024

        fun decode(data: ByteArray): RequestSyncPacket? {
            var off = 0
            var p: Int? = null
            var m: Long? = null
            var payload: ByteArray? = null
            var types: SyncTypeFlags? = null
            var sinceTimestamp: Long? = null

            while (off + 3 <= data.size) {
                val t = (data[off].toInt() and 0xFF); off += 1
                val len = ((data[off].toInt() and 0xFF) shl 8) or (data[off+1].toInt() and 0xFF); off += 2
                if (off + len > data.size) return null
                val v = data.copyOfRange(off, off + len); off += len
                when (t) {
                    0x01 -> if (len == 1) p = (v[0].toInt() and 0xFF)
                    0x02 -> if (len == 4) {
                        val mm = ((v[0].toLong() and 0xFF) shl 24) or
                                 ((v[1].toLong() and 0xFF) shl 16) or
                                 ((v[2].toLong() and 0xFF) shl 8) or
                                 (v[3].toLong() and 0xFF)
                        m = mm
                    }
                    0x03 -> {
                        if (v.size > MAX_ACCEPT_FILTER_BYTES) return null
                        payload = v
                    }
                    0x04 -> SyncTypeFlags.decode(v)?.let { types = it }
                    0x05 -> if (len == 8) {
                        var ts = 0L
                        for (b in v) ts = (ts shl 8) or (b.toLong() and 0xFF)
                        sinceTimestamp = ts
                    }
                    // No else branch: unknown TLVs (incl. 0x06 fragmentIdFilter) stay
                    // length-parsed and skipped — forward compatible.
                }
            }

            val pp = p ?: return null
            val mm = m ?: return null
            val dd = payload ?: return null
            if (pp < 1 || mm <= 0L) return null
            return RequestSyncPacket(pp, mm, dd, types, sinceTimestamp)
        }
    }
}
