package com.app.transport.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Feature capabilities a peer advertises in its announce packet (TLV type 0x05).
 * Compatible with the reference iOS `PeerCapabilities` OptionSet.
 *
 * Encoded as a little-endian bitfield with trailing zero bytes dropped, so the wire form grows only
 * when high bits are assigned. Decoders keep the low 64 bits and ignore any longer field, and
 * unknown bits are preserved verbatim — old clients skip the TLV entirely, new clients degrade
 * per-feature.
 */
@Serializable
@JvmInline
value class PeerCapabilities(val rawValue: ULong) {

    fun contains(capability: PeerCapabilities): Boolean =
        rawValue and capability.rawValue == capability.rawValue

    fun isEmpty(): Boolean = rawValue == 0uL

    operator fun plus(other: PeerCapabilities): PeerCapabilities = PeerCapabilities(rawValue or other.rawValue)

    /**
     * Minimal little-endian byte encoding; always at least one byte so an empty set is
     * distinguishable from an absent TLV.
     */
    fun encoded(): ByteArray {
        var value = rawValue
        val bytes = mutableListOf<Byte>()
        do {
            bytes.add(value.toByte())
            value = value shr 8
        } while (value != 0uL)
        return bytes.toByteArray()
    }

    companion object {
        val NONE = PeerCapabilities(0uL)

        val PREKEYS = PeerCapabilities(1uL shl 0)
        val WIFI_BULK = PeerCapabilities(1uL shl 1)
        val GATEWAY = PeerCapabilities(1uL shl 2)
        val GROUPS = PeerCapabilities(1uL shl 3)
        val BOARD = PeerCapabilities(1uL shl 4)
        val VOUCH = PeerCapabilities(1uL shl 5)
        val MESH_DIAGNOSTICS = PeerCapabilities(1uL shl 6)

        /**
         * Bridges the local mesh channel to the geohash-cell rendezvous on Nostr. Advertised
         * alongside a `bridgeGeohash` TLV (0x06) carrying the rendezvous cell.
         */
        val BRIDGE = PeerCapabilities(1uL shl 7)

        /**
         * What this client advertises: [VOUCH] and [PREKEYS].
         *
         * - [VOUCH]: transitive verification (vouch payload 0x12) — we emit and accept batches.
         * - [PREKEYS]: one-time prekey bundles (0x24) — we publish and verify+cache peers' bundles,
         *   and can open the forward-secret courier envelopes (v2) that sealing to them produces, so
         *   advertising the bit does not invite mail we would drop.
         *
         * Every other bit still names a wire feature we do not implement (group messages 0x25 as an
         * advertised capability, board posts 0x23, gateway/bridge carriers, Wi-Fi bulk transfer), and
         * advertising a bit we cannot honour would make peers route work to us that we silently drop.
         *
         * [MESH_DIAGNOSTICS] is deliberately withheld even though ping/pong now work: the reference
         * client implements them too and still leaves the bit out of its own `localSupported`, so
         * the bit's exact contract is unsettled. Turn bits on here as each feature lands.
         */
        val LOCAL_SUPPORTED = VOUCH + PREKEYS

        fun localSupported(gatewayEnabled: Boolean): PeerCapabilities =
            if (gatewayEnabled) LOCAL_SUPPORTED + GATEWAY else LOCAL_SUPPORTED

        /** Accepts any length; bytes beyond the low 64 bits are ignored for forward compatibility. */
        fun decode(data: ByteArray): PeerCapabilities {
            var value = 0uL
            for (index in 0 until minOf(data.size, 8)) {
                value = value or ((data[index].toUByte().toULong()) shl (8 * index))
            }
            return PeerCapabilities(value)
        }
    }
}
