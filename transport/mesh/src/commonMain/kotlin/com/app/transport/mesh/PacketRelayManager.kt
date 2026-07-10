package com.app.transport.mesh

import com.app.common.AppDispatchers
import com.app.common.utils.Log
import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import com.app.common.encoding.toHexString
import com.app.transport.MeshDebugToggles
import com.app.transport.protocol.MessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive

/**
 * Centralized packet relay management
 * 
 * This class handles all relay decisions and logic for bitchat packets.
 * All packets that aren't specifically addressed to us get processed here.
 */
internal class PacketRelayManager(
    private val myPeerID: String,
    private val debugSettingsManager: MeshDebugToggles,
    dispatchers: AppDispatchers = AppDispatchers(),
) {
    companion object {
        private const val TAG = "PacketRelayManager"
    }
    
    private fun isRelayEnabled(): Boolean = try {
        debugSettingsManager.packetRelayEnabled.value
    } catch (_: Exception) { true }

    // Logging moved to BluetoothPacketBroadcaster per actual transmission target
    
    // Delegate for callbacks
    var delegate: PacketRelayManagerDelegate? = null
    
    // Coroutines
    private val relayScope = CoroutineScope(dispatchers.io + SupervisorJob())
    
    /**
     * Main entry point for relay decisions
     * Only packets that aren't specifically addressed to us should be passed here
     */
    fun handlePacketRelay(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        
        Log.d(TAG, "Evaluating relay for packet type ${packet.type} from $peerID (TTL: ${packet.ttl})")
        
        // Double-check this packet isn't addressed to us
        if (isPacketAddressedToMe(packet)) {
            Log.d(TAG, "Packet addressed to us, skipping relay")
            return
        }
        
        // Skip our own packets
        if (peerID == myPeerID) {
            Log.d(TAG, "Packet from ourselves, skipping relay")
            return
        }
        
        // Check TTL and decrement
        if (packet.ttl == 0u.toUByte()) {
            Log.d(TAG, "TTL expired, not relaying packet")
            return
        }
        
        // Decrement TTL by 1
        val relayPacket = packet.copy(ttl = (packet.ttl - 1u).toUByte())
        Log.d(TAG, "Decremented TTL from ${packet.ttl} to ${relayPacket.ttl}")
        
        // Source-based routing: if route is set and includes us, try targeted next-hop forwarding
        val route = relayPacket.route
        if (!route.isNullOrEmpty()) {
            // Check for duplicate hops to prevent routing loops
            if (route.map { it.toHexString() }.toSet().size < route.size) {
                Log.w(TAG, "Packet with duplicate hops dropped")
                return
            }
            val myIdBytes = hexStringToPeerBytes(myPeerID)
            val index = route.indexOfFirst { it.contentEquals(myIdBytes) }
            if (index >= 0) {
                val nextHopIdHex: String? = run {
                    val nextIndex = index + 1
                    if (nextIndex < route.size) {
                        route[nextIndex].toHexString()
                    } else {
                        // We are the last intermediate; try final recipient as next hop
                        relayPacket.recipientID?.toHexString()
                    }
                }
                if (nextHopIdHex != null) {
                    val success = try { delegate?.sendToPeer(nextHopIdHex, RoutedPacket(relayPacket, peerID, routed.relayAddress)) } catch (_: Exception) { false } ?: false
                    if (success) {
                        Log.i(TAG, "📦 Source-route relay: ${peerID.take(8)} -> ${nextHopIdHex.take(8)} (type ${packet.type}, TTL ${relayPacket.ttl})")
                        return
                    } else {
                        Log.w(TAG, "Source-route next hop ${nextHopIdHex.take(8)} not directly connected; falling back to broadcast")
                    }
                }
            }
        }

        // Apply relay policy (RelayController port) based on packet type, LOCAL link
        // degree and the debug switch. The decision consumes the ORIGINAL incoming TTL
        // and produces the outgoing TTL itself (clamp-then-decrement, iOS parity).
        if (!isRelayEnabled()) {
            Log.d(TAG, "Relay decision: relay disabled, NOT relaying packet type ${packet.type}")
            return
        }
        val decision = decideRelay(packet)
        if (decision.shouldRelay) {
            relayPacket(RoutedPacket(packet.copy(ttl = decision.newTTL), peerID, routed.relayAddress))
        } else {
            Log.d(TAG, "Relay decision: NOT relaying packet type ${packet.type} (ttl=${packet.ttl}, degree=${delegate?.getLocalDegree()})")
        }
    }

    /**
     * iOS RelayController.decide() inputs derived from the packet. senderIsSelf and
     * recipientIsSelf are already filtered by the caller, so they are passed false.
     */
    private fun decideRelay(packet: BitchatPacket): RelayDecision {
        val mt = MessageType.fromValue(packet.type)
        val recipientID = packet.recipientID
        val broadcastRecipient = delegate?.getBroadcastRecipient()
        val isDirected = recipientID != null &&
            !(broadcastRecipient != null && recipientID.contentEquals(broadcastRecipient))
        // Directed ping/pong ride the same deterministic relay treatment as directed Noise
        // traffic (always relay, no TTL clamp) so the RTT and hop count a probe reports reflect
        // the real path — iOS BLEReceivePipeline folds them into `isDirectedEncrypted` too.
        val ridesDirectedPath = mt == MessageType.NOISE_ENCRYPTED ||
            mt == MessageType.PING ||
            mt == MessageType.PONG
        return RelayController.decide(
            ttl = packet.ttl,
            senderIsSelf = false,
            recipientIsSelf = false,
            isDirectedEncrypted = ridesDirectedPath && isDirected,
            isFragment = mt == MessageType.FRAGMENT,
            isDirectedFragment = mt == MessageType.FRAGMENT && isDirected,
            isHandshake = mt == MessageType.NOISE_HANDSHAKE,
            isAnnounce = mt == MessageType.ANNOUNCE,
            isRequestSync = mt == MessageType.REQUEST_SYNC,
            degree = delegate?.getLocalDegree() ?: 0,
        )
    }
    
    /**
     * Check if a packet is specifically addressed to us
     */
    internal fun isPacketAddressedToMe(packet: BitchatPacket): Boolean {
        val recipientID = packet.recipientID
        
        // No recipient means broadcast (not addressed to us specifically)
        if (recipientID == null) {
            return false
        }
        
        // Check if it's a broadcast recipient
        val broadcastRecipient = delegate?.getBroadcastRecipient()
        if (broadcastRecipient != null && recipientID.contentEquals(broadcastRecipient)) {
            return false
        }
        
        // Check if recipient matches our peer ID
        val recipientIDString = recipientID.toHexString()
        return recipientIDString == myPeerID
    }
    
    /**
     * Actually broadcast the packet for relay
     */
    private fun relayPacket(routed: RoutedPacket) {
        Log.d(TAG, "🔄 Relaying packet type ${routed.packet.type} with TTL ${routed.packet.ttl}")
        delegate?.broadcastPacket(routed)
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Packet Relay Manager Debug Info ===")
            appendLine("Relay Scope Active: ${relayScope.isActive}")
            appendLine("My Peer ID: ${myPeerID}")
            appendLine("Network Size: ${delegate?.getNetworkSize() ?: "unknown"}")
        }
    }
    
    /**
     * Shutdown the relay manager
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down PacketRelayManager")
        relayScope.cancel()
    }
}

/**
 * Delegate interface for packet relay manager callbacks
 */
internal interface PacketRelayManagerDelegate {
    // Network information
    fun getNetworkSize(): Int

    /**
     * LOCAL degree: number of directly connected links (radio neighbors). This is what
     * the RelayController TTL clamp keys on — NOT getNetworkSize(), which counts total
     * multi-hop peers (in a 1000-peer mesh a node has ~4-8 direct links).
     */
    fun getLocalDegree(): Int
    fun getBroadcastRecipient(): ByteArray
    
    // Packet operations
    fun broadcastPacket(routed: RoutedPacket)
    fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean
}

private fun hexStringToPeerBytes(hex: String): ByteArray {
    val result = ByteArray(8)
    var idx = 0
    var out = 0
    while (idx + 1 < hex.length && out < 8) {
        val b = hex.substring(idx, idx + 2).toIntOrNull(16)?.toByte() ?: 0
        result[out++] = b
        idx += 2
    }
    return result
}
