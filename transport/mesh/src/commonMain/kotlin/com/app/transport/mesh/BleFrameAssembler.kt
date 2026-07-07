@file:OptIn(ExperimentalTime::class)

package com.app.transport.mesh

import com.app.common.utils.Log
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Reassembles BitchatPacket frames from a chunked BLE byte stream.
 *
 * Port of the iOS reference client's NotificationStreamAssembler (and the write-side
 * BLEInboundWriteBuffer): iOS sends GATT writes chunked to `maximumWriteValueLength`
 * (ATT MTU − 3) and expects the peripheral to reassemble; symmetrically, notifications
 * larger than the central's MTU arrive split. Feeding every inbound value through this
 * assembler makes both directions tolerant of chunking. A complete frame per value —
 * today's happy path — passes through unchanged, one frame per append.
 *
 * Framing: the BitchatPacket header declares the exact frame length
 * (header + senderID [+ recipientID] [+ route] + payload [+ signature]), so frames are
 * extracted as soon as enough bytes accumulate. Leading bytes that cannot start a frame
 * (protocol version ≠ 1/2, e.g. trailing message padding) are discarded byte-wise, same
 * as iOS. A partially received frame that stalls longer than [STALL_RESET_MS] resets the
 * stream so one lost chunk cannot wedge the link.
 */
internal class BleFrameAssembler(
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    companion object {
        private const val TAG = "BleFrameAssembler"

        // iOS TransportConfig.bleNotificationAssemblerHardCapBytes
        const val HARD_CAP_BYTES: Int = 8 * 1024 * 1024

        // iOS TransportConfig.bleAssemblerStallResetMs
        const val STALL_RESET_MS: Long = 250L

        // BinaryProtocol layout constants (v1/v2 headers, big-endian)
        private const val V1_HEADER_SIZE = 14
        private const val V2_HEADER_SIZE = 16
        private const val SENDER_ID_SIZE = 8
        private const val RECIPIENT_ID_SIZE = 8
        private const val SIGNATURE_SIZE = 64
        private const val FLAGS_OFFSET = 11
        private const val LENGTH_OFFSET = 12
        private const val FLAG_HAS_RECIPIENT = 0x01
        private const val FLAG_HAS_SIGNATURE = 0x02
        private const val FLAG_HAS_ROUTE = 0x08
    }

    private var buffer = ByteArray(0)
    private var pendingFrameSince: Long? = null
    private var pendingFrameExpectedLength = 0

    private fun reset() {
        buffer = ByteArray(0)
        pendingFrameSince = null
        pendingFrameExpectedLength = 0
    }

    /**
     * Strips a leading PKCS#7 padding run (MessagePadding: every pad byte equals the pad
     * length) left behind after extracting a padded frame. Mirrors the iOS assembler.
     */
    private fun discardLeadingPaddingIfPresent(): Boolean {
        val first = buffer.firstOrNull() ?: return false
        val padLength = first.toInt() and 0xFF
        if (padLength == 1 || padLength == 2) return false // valid protocol versions
        if (padLength <= 0 || padLength > buffer.size) return false
        for (i in 0 until padLength) {
            if (buffer[i] != first) return false
        }
        buffer = buffer.copyOfRange(padLength, buffer.size)
        pendingFrameSince = null
        pendingFrameExpectedLength = 0
        return true
    }

    /**
     * Appends one inbound GATT value and returns every complete frame now extractable.
     * Each returned frame starts at a valid header and has exactly the length the header
     * declares — decodable by BinaryProtocol without padding.
     */
    fun append(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()

        buffer = if (buffer.isEmpty()) chunk.copyOf() else buffer + chunk

        if (buffer.size > HARD_CAP_BYTES) {
            Log.w(TAG, "Assembler overflow (${buffer.size} bytes); dropping partial frame")
            reset()
            return emptyList()
        }

        val frames = mutableListOf<ByteArray>()
        val now = nowMillis()

        while (buffer.size >= V1_HEADER_SIZE + SENDER_ID_SIZE) {
            val version = buffer[0].toInt() and 0xFF
            if (version != 1 && version != 2) {
                // Not a frame start: strip a whole padding run when recognizable,
                // otherwise discard one byte — same as iOS.
                if (!discardLeadingPaddingIfPresent()) {
                    buffer = buffer.copyOfRange(1, buffer.size)
                    pendingFrameSince = null
                    pendingFrameExpectedLength = 0
                }
                continue
            }

            val headerSize = if (version == 2) V2_HEADER_SIZE else V1_HEADER_SIZE
            val framePrefix = headerSize + SENDER_ID_SIZE
            if (buffer.size < framePrefix) break

            val flags = buffer[FLAGS_OFFSET].toInt() and 0xFF
            val hasRecipient = (flags and FLAG_HAS_RECIPIENT) != 0
            val hasSignature = (flags and FLAG_HAS_SIGNATURE) != 0
            val hasRoute = version >= 2 && (flags and FLAG_HAS_ROUTE) != 0

            val payloadLength = if (version == 2) {
                ((buffer[LENGTH_OFFSET].toInt() and 0xFF) shl 24) or
                    ((buffer[LENGTH_OFFSET + 1].toInt() and 0xFF) shl 16) or
                    ((buffer[LENGTH_OFFSET + 2].toInt() and 0xFF) shl 8) or
                    (buffer[LENGTH_OFFSET + 3].toInt() and 0xFF)
            } else {
                ((buffer[LENGTH_OFFSET].toInt() and 0xFF) shl 8) or
                    (buffer[LENGTH_OFFSET + 1].toInt() and 0xFF)
            }

            var frameLength = framePrefix + payloadLength
            if (hasRecipient) frameLength += RECIPIENT_ID_SIZE
            if (hasSignature) frameLength += SIGNATURE_SIZE
            if (hasRoute) {
                val routeCountOffset = framePrefix + (if (hasRecipient) RECIPIENT_ID_SIZE else 0)
                if (buffer.size <= routeCountOffset) break
                val routeCount = buffer[routeCountOffset].toInt() and 0xFF
                frameLength += 1 + routeCount * SENDER_ID_SIZE
            }

            if (frameLength !in 1..HARD_CAP_BYTES) {
                Log.w(TAG, "Frame length $frameLength invalid (cap=$HARD_CAP_BYTES); resetting stream")
                reset()
                break
            }

            if (buffer.size < frameLength) {
                // Incomplete frame: wait for more chunks, but reset a stalled stream.
                if (pendingFrameSince == null || frameLength != pendingFrameExpectedLength) {
                    pendingFrameSince = now
                    pendingFrameExpectedLength = frameLength
                } else if (now - pendingFrameSince!! >= STALL_RESET_MS) {
                    Log.d(TAG, "Resetting assembler after stalling ${frameLength - buffer.size}B short for ${STALL_RESET_MS}ms")
                    reset()
                }
                break
            }

            pendingFrameSince = null
            pendingFrameExpectedLength = 0
            frames.add(buffer.copyOfRange(0, frameLength))
            buffer = buffer.copyOfRange(frameLength, buffer.size)
            discardLeadingPaddingIfPresent()
        }

        // Pure zero tail can never become a frame start (version 0) — drop it eagerly.
        if (buffer.isNotEmpty() && buffer.all { it == 0.toByte() }) reset()

        return frames
    }
}
