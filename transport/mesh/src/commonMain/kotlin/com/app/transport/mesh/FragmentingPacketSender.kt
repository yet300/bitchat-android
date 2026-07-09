package com.app.transport.mesh

import co.touchlab.stately.collections.ConcurrentMutableMap
import com.app.common.encoding.hexEncodedString
import com.app.common.utils.Log
import com.app.transport.crypto.Sha256
import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

/**
 * Shared transport send wrapper that applies bitchat packet fragmentation and
 * transfer progress before a transport writes packets to its concrete medium.
 *
 * Ported from upstream (wifi-aware branch); upstream's static TransferProgressManager
 * is replaced by the graph-owned instance.
 */
class FragmentingPacketSender(
    private val scope: CoroutineScope,
    private val fragmentManager: FragmentManager?,
    private val transferProgressManager: TransferProgressManager,
    private val logTag: String,
    private val interFragmentDelayMs: Long = 20L,
    // Simultaneous large media transfers (iOS bleMaxConcurrentTransfers). A file transfer beyond the
    // limit waits for a slot instead of flooding the radio and starving interactive traffic.
    maxConcurrentTransfers: Int = 2,
) {
    private val transferJobs = ConcurrentMutableMap<String, Job>()
    private val transferSlots = Semaphore(maxConcurrentTransfers.coerceAtLeast(1))

    fun send(
        routed: RoutedPacket,
        description: String,
        sendSingle: (RoutedPacket) -> Boolean
    ): Boolean {
        val transferId = transferIdFor(routed)
        val packets = packetsForTransport(routed.packet) ?: return false
        val total = packets.size

        if (total <= 1) {
            if (transferId != null) {
                transferProgressManager.start(transferId, 1)
            }
            val sent = sendSingle(routed.copy(packet = packets.first(), transferId = transferId))
            if (sent && transferId != null) {
                transferProgressManager.progress(transferId, 1, 1)
                transferProgressManager.complete(transferId, 1)
            }
            return sent
        }

        Log.d(logTag, "Fragmenting packet type ${routed.packet.type} into $total fragments for $description")
        if (transferId != null) {
            transferProgressManager.start(transferId, total)
        }

        // Only bulk media (file transfers) is slot-gated; interactive fragmented frames are not, so
        // a running large transfer never blocks a message from going out.
        val gated = routed.packet.type == MessageType.FILE_TRANSFER.value
        val job = scope.launch(start = CoroutineStart.LAZY) {
            if (gated) transferSlots.acquire()
            try {
                var sent = 0
                for (packet in packets) {
                    if (!isActive) return@launch
                    if (transferId != null && transferJobs[transferId]?.isCancelled == true) return@launch

                    val fragment = routed.copy(packet = packet, transferId = transferId)
                    val delivered = try {
                        sendSingle(fragment)
                    } catch (e: Exception) {
                        Log.e(logTag, "Fragment send failed for $description: ${e.message}", e)
                        false
                    }

                    if (!delivered) {
                        Log.w(logTag, "Stopping fragmented send for $description after $sent/$total fragments")
                        return@launch
                    }

                    sent += 1
                    if (transferId != null) {
                        transferProgressManager.progress(transferId, sent, total)
                    }
                    if (sent < total) {
                        delay(interFragmentDelayMs)
                    }
                }

                if (transferId != null) {
                    transferProgressManager.complete(transferId, total)
                }
            } finally {
                if (gated) transferSlots.release()
            }
        }

        if (transferId != null) {
            transferJobs[transferId] = job
            job.invokeOnCompletion { if (transferJobs[transferId] === job) transferJobs.remove(transferId) }
        }
        job.start()
        return true
    }

    fun cancelTransfer(transferId: String): Boolean {
        val job = transferJobs.remove(transferId) ?: return false
        job.cancel()
        return true
    }

    private fun packetsForTransport(packet: BitchatPacket): List<BitchatPacket>? {
        if (packet.type == MessageType.FRAGMENT.value) {
            return listOf(packet)
        }

        val manager = fragmentManager ?: return listOf(packet)
        return try {
            val fragments = manager.createFragments(packet)
            if (fragments.isEmpty()) {
                Log.e(logTag, "Fragment manager returned no packets for packet type ${packet.type}")
                null
            } else {
                fragments
            }
        } catch (e: Exception) {
            Log.e(logTag, "Fragment creation failed for packet type ${packet.type}: ${e.message}", e)
            null
        }
    }

    private fun transferIdFor(routed: RoutedPacket): String? {
        routed.transferId?.let { return it }
        val packet = routed.packet
        return if (packet.type == MessageType.FILE_TRANSFER.value) {
            sha256Hex(packet.payload)
        } else {
            null
        }
    }

    private fun sha256Hex(bytes: ByteArray): String = try {
        Sha256.digest(bytes).hexEncodedString()
    } catch (_: Exception) {
        bytes.size.toString(16)
    }
}
