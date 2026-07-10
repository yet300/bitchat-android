package com.app.transport.mesh

import com.app.common.encoding.toHexString
import com.app.common.utils.Log
import com.app.transport.MeshTrafficLog
import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.SpecialRecipients
import com.app.transport.sync.PacketIdUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One BLE link the local node can write to right now. */
data class BleNeighbor(
    val linkAddress: String,
    /** True for an outbound (client/central) link, false for an inbound (server/peripheral) one. */
    val isClient: Boolean,
    /** Logical peer bound to this link, if the handshake got that far. */
    val peerID: String?,
)

/**
 * The thin per-platform radio SPI under [BleSendCore]: everything above the raw GATT
 * write/notify — fragmentation, target selection, source routing, anti-loop, padding,
 * telemetry — lives in the shared core.
 */
interface BleRadioLink {
    /**
     * Snapshot of writable links, in send order: server (inbound) links first, then client
     * (outbound) links — the order both platforms have always used.
     */
    fun neighbors(): List<BleNeighbor>

    /** Writes one already-encoded frame to one link. Must not throw. */
    fun writeToNeighbor(neighbor: BleNeighbor, frame: ByteArray): Boolean

    /** Resolves the logical peer bound to a link address (relay-loop bookkeeping). */
    fun peerForAddress(linkAddress: String): String?
}

/**
 * Shared BLE send core (C1 unification): the send tree previously duplicated between the Android
 * BluetoothPacketBroadcaster and the Apple CoreBluetoothConnectionManager.
 *
 * Owns, once, in commonMain:
 *  - fragmentation, inter-fragment pacing, transfer progress and cancel ([FragmentingPacketSender]),
 *  - `toBinaryData(padding = shouldPadForBLE(type))` — the iOS-compatible wire encoding,
 *  - source routing for originating packets (behind [sourceRoutingEnabled]: the Apple bearer never
 *    had it, so it stays off there until the owner enables it deliberately),
 *  - directed-send-to-neighbor with broadcast fallback,
 *  - broadcast with relay/sender anti-loop,
 *  - per-target relay telemetry through the [MeshTrafficLog] port (null = silent, Apple today).
 *
 * Broadcast sends are serialized through a bounded, priority-ordered [BleOutboundFrameQueue] with a
 * single consumer — the same single-writer / FIFO-per-priority discipline as the JVM `actor` the
 * Android broadcaster used, but now capped (a message storm can no longer grow it without bound) and
 * priority-aware (own traffic dequeues before relay; on overflow the relay/media tail is shed first
 * with telemetry). [sendToPeer] stays synchronous on the caller, exactly as before on both platforms.
 */
class BleSendCore(
    private val scope: CoroutineScope,
    fragmentManager: FragmentManager?,
    transferProgressManager: TransferProgressManager,
    private val myPeerID: String,
    private val radio: BleRadioLink,
    private val trafficLog: MeshTrafficLog?,
    private val sourceRoutingEnabled: Boolean,
    private val logTag: String,
    private val config: BleRadioConfig = BleRadioConfig(),
) {
    private var nicknameResolver: ((String) -> String?)? = null

    private val fragmentingSender =
        FragmentingPacketSender(
            scope, fragmentManager, transferProgressManager, logTag,
            interFragmentDelayMs = config.fragmentSpacingMs,
            maxConcurrentTransfers = config.maxConcurrentTransfers,
        )

    private val sendQueue = BleOutboundFrameQueue(config.sendQueueCapacity) {
        trafficLog?.onOutboundDropped(BearerId.BLE)
    }

    init {
        scope.launch {
            try {
                while (isActive) sendSinglePacketInternal(sendQueue.receive())
            } catch (_: ClosedReceiveChannelException) {
                // shutdown() closed the queue — end the consumer cleanly, as the old `for (x in
                // channel)` loop did.
            }
        }
    }

    fun setNicknameResolver(resolver: (String) -> String?) {
        nicknameResolver = resolver
    }

    /** Fragments (if needed) and queues each frame for the serialized broadcast consumer. */
    fun broadcastPacket(routed: RoutedPacket) {
        fragmentingSender.send(routed, "type ${routed.packet.type}") { fragment ->
            enqueue(fragment)
        }
    }

    fun cancelTransfer(transferId: String): Boolean = fragmentingSender.cancelTransfer(transferId)

    /**
     * Targeted send to a directly connected peer, bypassing the queue (historical behavior on
     * both platforms). Server links are preferred over client links.
     */
    fun sendToPeer(targetPeerID: String, routed: RoutedPacket): Boolean {
        val packet = routed.packet
        // iOS-compatible: only Noise frames are padded over BLE (BLEPacketPaddingPolicy).
        val data = packet.toBinaryData(padding = BLEPacketPaddingPolicy.shouldPadForBLE(packet.type)) ?: return false
        for (neighbor in radio.neighbors()) {
            if (neighbor.peerID != targetPeerID) continue
            if (radio.writeToNeighbor(neighbor, data)) {
                logPacketRelay(routed, toPeer = targetPeerID, toAddress = neighbor.linkAddress)
                return true
            }
        }
        return false
    }

    fun shutdown() {
        sendQueue.close()
    }

    fun getDebugInfo(): String = buildString {
        appendLine("=== BLE Send Core Debug Info ===")
        appendLine("Scope Active: ${scope.isActive}")
        appendLine("Source Routing: $sourceRoutingEnabled")
    }

    private fun enqueue(routed: RoutedPacket): Boolean {
        // Bounded priority queue: never rejects — sheds the lowest-priority (relay/bulk) tail on
        // overflow with telemetry, so our own interactive frames are the last to be dropped.
        sendQueue.offer(routed, BleOutboundPriority.of(routed))
        return true
    }

    private fun sendSinglePacketInternal(routed: RoutedPacket) {
        val packet = routed.packet
        // iOS-compatible: only Noise frames are padded over BLE (BLEPacketPaddingPolicy).
        val data = packet.toBinaryData(padding = BLEPacketPaddingPolicy.shouldPadForBLE(packet.type)) ?: return
        val senderID = packet.senderID.toHexString()
        val routeInfo = packet.route?.takeIf { it.isNotEmpty() }?.let { "routed: ${it.size} hops" }
        val neighbors = radio.neighbors()

        // Queued directed send (gossip-sync responses): deliver only to the target peer's
        // link. If the link is gone, fall through to the broadcast fallback below — the
        // same historical fallback MeshNetwork.sendToPeer uses on the direct path.
        val directedTarget = routed.directedPeerID
        if (directedTarget != null) {
            for (neighbor in neighbors) {
                if (neighbor.peerID != directedTarget) continue
                if (radio.writeToNeighbor(neighbor, data)) {
                    logPacketRelay(routed, neighbor.peerID, neighbor.linkAddress, packet.version, routeInfo)
                    return
                }
            }
        }

        // Source routing for originating packets: send ONLY to the first hop when we authored the
        // packet and it carries an explicit route; fall back to broadcast if the hop is gone.
        if (sourceRoutingEnabled && senderID == myPeerID && !packet.route.isNullOrEmpty()) {
            val firstHop = packet.route!![0].toHexString()
            for (hop in neighbors) {
                if (hop.peerID != firstHop) continue
                if (radio.writeToNeighbor(hop, data)) {
                    logPacketRelay(routed, hop.peerID, hop.linkAddress, packet.version, routeInfo)
                    return
                }
            }
            Log.w(logTag, "Source routing: first hop $firstHop unreachable, broadcasting instead")
        }

        // Directed send when the unicast recipient is a direct neighbor (server link first).
        val recipientID = packet.recipientID
        if (recipientID != null && !recipientID.contentEquals(SpecialRecipients.BROADCAST)) {
            val recipient = recipientID.toHexString()
            for (neighbor in neighbors) {
                if (neighbor.peerID != recipient) continue
                if (radio.writeToNeighbor(neighbor, data)) {
                    logPacketRelay(routed, neighbor.peerID, neighbor.linkAddress, packet.version, routeInfo)
                    return
                }
            }
        }

        // Broadcast with loop avoidance (skip the relayer link and the original sender),
        // then a deterministic per-message fanout subset for eligible types (iOS
        // BLEFanoutSelector): non-exempt broadcasts go to ~log2(count)+1 links chosen by
        // SHA256(messageID + "::" + linkID); fragment/announce/requestSync go to ALL.
        val eligible = neighbors.filter {
            it.linkAddress != routed.relayAddress && it.peerID != senderID
        }
        val targets = if (BleFanoutSelector.shouldSubset(packet.type)) {
            val chosen = BleFanoutSelector.selectSubset(
                linkIds = eligible.map { it.linkAddress },
                k = BleFanoutSelector.subsetSize(eligible.size),
                seed = PacketIdUtil.computeIdHex(packet), // the packet's dedup key
            )
            eligible.filter { it.linkAddress in chosen }
        } else {
            eligible
        }
        targets.forEach { neighbor ->
            if (radio.writeToNeighbor(neighbor, data)) {
                logPacketRelay(routed, neighbor.peerID, neighbor.linkAddress, packet.version, routeInfo)
            }
        }
    }

    private fun logPacketRelay(
        routed: RoutedPacket,
        toPeer: String?,
        toAddress: String,
        packetVersion: UByte = 1u,
        routeInfo: String? = null,
    ) {
        val log = trafficLog ?: return
        try {
            val packet = routed.packet
            val typeName = MessageType.fromValue(packet.type)?.name ?: packet.type.toString()
            val senderPeerID = routed.peerID ?: packet.senderID.toHexString()
            val incomingAddr = routed.relayAddress
            val incomingPeer = incomingAddr?.let { radio.peerForAddress(it) }
            log.logOutgoing(
                packetType = typeName,
                toPeerID = toPeer,
                toNickname = toPeer?.let { nicknameResolver?.invoke(it) },
                toDeviceAddress = toAddress,
                previousHopPeerID = incomingPeer,
                packetVersion = packetVersion,
                routeInfo = routeInfo,
            )
            log.logPacketRelayDetailed(
                packetType = typeName,
                senderPeerID = senderPeerID,
                senderNickname = nicknameResolver?.invoke(senderPeerID),
                fromPeerID = incomingPeer,
                fromNickname = incomingPeer?.let { nicknameResolver?.invoke(it) },
                fromDeviceAddress = incomingAddr,
                toPeerID = toPeer,
                toNickname = toPeer?.let { nicknameResolver?.invoke(it) },
                toDeviceAddress = toAddress,
                ttl = packet.ttl,
                isRelay = true,
                packetVersion = packetVersion,
                routeInfo = routeInfo,
            )
        } catch (_: Exception) {
            // Telemetry must never break the send path.
        }
    }
}
