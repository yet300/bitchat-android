package com.app.transport.mesh

import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.MessageType

/**
 * Result of a single raw chunk write to a BLE link (the only thing the platform still decides
 * about a write). Ported conceptually from the reference iOS backpressure model:
 *  - [SENT]  the stack accepted the chunk (`notifyCharacteristicChanged`/`updateValue` returned
 *            true, or `canSendWriteWithoutResponse` was true and `writeValue` was issued),
 *  - [BUSY]  the stack's transmit queue is full right now — retry on the readiness signal
 *            (`onNotificationSent` / `peripheralManagerIsReady` / `onCharacteristicWrite` /
 *            `peripheralIsReady`); the chunk is NOT lost,
 *  - [GONE]  the link is structurally unusable (device/characteristic/gatt gone) — give up.
 */
enum class ChunkWriteResult { SENT, BUSY, GONE }

/**
 * The thin platform primitive under [BleOutboundDispatcher]: write ONE already-sized chunk
 * (`≤ maxChunkBytes`) to one link right now. Must not throw. Everything else — chunking, ordering,
 * busy-retry, pacing, per-link capping — lives in commonMain.
 */
fun interface BleChunkWriter {
    fun writeChunk(chunk: ByteArray): ChunkWriteResult
}

/**
 * Outbound priority for a frame. Lower ordinal = higher priority = kept under back-pressure. Mirrors
 * the reference iOS [BLEOutboundWritePriority]/[BLEOutboundPacketPolicy] (own traffic before relay,
 * bulk media last) so that, when a bounded queue overflows, the mesh sheds relay/media tails first
 * and never our own interactive packets.
 */
enum class BleOutboundPriority {
    OWN_HIGH,       // our own control/message frames
    OWN_FRAGMENT,   // our own fragmented media
    RELAY_HIGH,     // relayed control/message frames
    RELAY_BULK,     // relayed fragments / bulk media
    ;

    companion object {
        /** own > relay; within each, interactive frames beat fragmented/bulk media. */
        fun of(routed: RoutedPacket): BleOutboundPriority {
            val own = routed.relayAddress == null
            val bulk = when (routed.packet.type) {
                MessageType.FRAGMENT.value, MessageType.FILE_TRANSFER.value -> true
                else -> false
            }
            return when {
                own && !bulk -> OWN_HIGH
                own && bulk -> OWN_FRAGMENT
                !own && !bulk -> RELAY_HIGH
                else -> RELAY_BULK
            }
        }
    }
}
