package com.app.transport.mesh

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.aware.PeerHandle
import android.net.wifi.aware.PublishDiscoverySession
import android.net.wifi.aware.WifiAwareNetworkInfo
import android.net.wifi.aware.WifiAwareNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.system.OsConstants
import androidx.annotation.RequiresApi
import com.app.common.encoding.toHexString
import com.app.common.utils.Log
import com.app.transport.MeshConstants
import com.app.transport.mesh.aware.SyncedSocket
import com.app.transport.model.RoutedPacket
import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Inet6Address
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * NDP/TCP data-path layer of [WifiAwareBearer]: server-socket offering, client socket
 * establishment over the Aware network, TCP + discovery keep-alives, the per-socket
 * receive loop, and disconnection cleanup. The bearer keeps discovery/session lifecycle
 * (attach, publish/subscribe, restart, maintenance) and role selection; this class owns
 * everything below the "server-ready" handshake. Extracted verbatim from the bearer —
 * behavior-preserving, one instance per bearer sharing its state via internal access.
 */
internal class WifiAwareDataPath(private val bearer: WifiAwareBearer) {

    companion object {
        private const val TAG = "WifiAwareBearer"
        private val MAX_TTL: UByte = MeshConstants.MESSAGE_TTL_HOPS
        private const val PSK = "bitchat_secret"
        // Network request / socket timeouts
        private const val NETWORK_REQUEST_TIMEOUT_MS = 30_000
        private const val ACCEPT_TIMEOUT_MS = 30_000
        private const val CLIENT_CONNECT_TIMEOUT_MS = 7_000
        private const val CLIENT_SOCKET_READY_DELAY_MS = 750L
        private const val CLIENT_SOCKET_RETRY_DELAY_MS = 750L
        private const val CLIENT_SOCKET_ATTEMPTS = 3
    }

    // -----------------------------------------------------------------
    // Neighbor bookkeeping (replaces upstream meshCore.setDirectConnection/removePeer)
    // -----------------------------------------------------------------

    private fun onPeerSocketEstablished(peerId: String, inbound: Boolean) {
        val addr = bearer.linkAddressFor(peerId)
        bearer.neighborsState.update { links ->
            links.filterNot { it.peerID == peerId || it.deviceAddress == addr }.toSet() +
                PeerLink(peerId, addr, isInbound = inbound)
        }
        try { bearer.telemetry.logPeerConnection(peerId, "unknown", addr, inbound) } catch (_: Exception) { }
        bearer.eventsFlow.tryEmit(BearerEvent.LinkConnected(addr))
    }

    private fun onPeerLinkLost(peerId: String) {
        val addr = bearer.linkAddressFor(peerId)
        var hadLink = false
        bearer.neighborsState.update { links ->
            val filtered = links.filterNot { it.peerID == peerId || it.deviceAddress == addr }.toSet()
            hadLink = filtered.size != links.size
            filtered
        }
        if (hadLink) {
            try { bearer.telemetry.logPeerDisconnection(peerId, "unknown", addr) } catch (_: Exception) { }
            bearer.eventsFlow.tryEmit(BearerEvent.LinkDisconnected(addr))
        }
    }

    private fun onPeerRebound(previousPeerId: String, resolvedPeerId: String, inbound: Boolean) {
        bearer.neighborsState.update { links ->
            links.filterNot {
                it.peerID == previousPeerId || it.peerID == resolvedPeerId ||
                    it.deviceAddress == bearer.linkAddressFor(previousPeerId) ||
                    it.deviceAddress == bearer.linkAddressFor(resolvedPeerId)
            }.toSet() + PeerLink(resolvedPeerId, bearer.linkAddressFor(resolvedPeerId), isInbound = inbound)
        }
        bearer.eventsFlow.tryEmit(BearerEvent.LinkDisconnected(bearer.linkAddressFor(previousPeerId)))
        bearer.eventsFlow.tryEmit(BearerEvent.LinkConnected(bearer.linkAddressFor(resolvedPeerId)))
    }

    /**
     * Handles subscriber ping: spawns a server socket and responds with connection info.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun handleSubscriberPing(
        pubSession: PublishDiscoverySession,
        peerHandle: PeerHandle
    ) {
        val peerId = bearer.handleToPeerId[peerHandle] ?: return
        if (!bearer.amIServerFor(peerId)) return

        val connectionTracker = bearer.connectionTracker
        if (connectionTracker.isConnected(peerId)) {
            Log.v(TAG, "↪ already connected to $peerId, skipping serve")
            return
        }
        if (connectionTracker.hasOpenServerSocket(peerId)) {
            Log.v(TAG, "↪ already serving $peerId, skipping")
            return
        }
        if (connectionTracker.hasPendingDataPathRequest(peerId)) {
            val pending = connectionTracker.pendingDataPathPeerIds(peerId).joinToString(", ") { it.take(8) }
            Log.d(TAG, "SERVER: deferring serve for ${peerId.take(8)}; pending Aware data path(s): $pending")
            return
        }
        if (!connectionTracker.addPendingConnection(peerId)) {
            return
        }

        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            val anyIpv6 = Inet6Address.getByAddress(ByteArray(16))
            ss.bind(java.net.InetSocketAddress(anyIpv6, 0))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind server socket", e)
            handleNetworkFailure(peerId)
            return
        }

        connectionTracker.addServerSocket(peerId, ss)
        val port = ss.localPort

        Log.d(TAG, "SERVER: listening for ${peerId.take(8)} on ${ss.localSocketAddress}")

        val spec = WifiAwareNetworkSpecifier.Builder(pubSession, peerHandle)
            .setPskPassphrase(PSK)
            .setPort(port)
            .setTransportProtocol(OsConstants.IPPROTO_TCP)
            .build()
        // Default capabilities include NET_CAPABILITY_NOT_VPN.
        // Keeping defaults for hardware interface handle acquisition compatibility with global VPNs.
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(spec)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            @Volatile private var activeSocket: SyncedSocket? = null
            private val acceptStarted = AtomicBoolean(false)

            override fun onAvailable(network: Network) {
                Log.i(TAG, "SERVER: onAvailable() - Aware network is ready for ${peerId.take(8)}")
                // Only accept once per network request
                if (!acceptStarted.compareAndSet(false, true)) return
                // Offload the blocking accept() off the callback thread so we never stall
                // the (main-thread) ConnectivityManager callback dispatcher.
                bearer.listenerExec.execute {
                    try {
                        try { ss.soTimeout = ACCEPT_TIMEOUT_MS } catch (_: Exception) {}
                        val client = ss.accept()
                        Log.i(TAG, "SERVER: Accepted raw TCP connection from ${peerId.take(8)}")
                        try { network.bindSocket(client) } catch (e: Exception) { Log.w(TAG, "Server bindSocket EPERM: ${e.message}") }
                        client.keepAlive = true
                        Log.i(TAG, "SERVER: Bound and established TCP with ${peerId.take(8)} addr=${client.inetAddress?.hostAddress}")
                        val synced = SyncedSocket(client)
                        activeSocket = synced
                        connectionTracker.onClientConnected(peerId, synced)
                        // We only ever accept a single data socket per server request. Close the
                        // listening ServerSocket now so it can't block a future re-serve (its
                        // presence makes hasOpenServerSocket() true for the life of the process)
                        // and so we free the fd/port promptly.
                        connectionTracker.closeServerSocket(peerId)
                        onPeerSocketEstablished(peerId, inbound = true)
                        bearer.listenerExec.execute { listenToPeer(synced, peerId) }
                        handleSubscriberKeepAlive(synced, peerId, pubSession, peerHandle)
                    } catch (ioe: IOException) {
                        if (ss.isClosed || !bearer.isRadioActive) {
                            Log.d(TAG, "SERVER: accept stopped for ${peerId.take(8)} after socket cleanup")
                        } else {
                            Log.e(TAG, "SERVER: accept failed for ${peerId.take(8)}", ioe)
                            handleNetworkFailure(peerId)
                        }
                    }
                }
            }

            override fun onUnavailable() {
                Log.e(TAG, "SERVER: onUnavailable() - Failed to acquire Aware network for ${peerId.take(8)} (timeout or refused)")
                handleNetworkFailure(peerId)
            }

            override fun onLost(network: Network) {
                handlePeerDisconnection(peerId, activeSocket)
                Log.i(TAG, "SERVER: WiFi Aware network lost for ${peerId.take(8)}")
            }
        }

        connectionTracker.addNetworkCallback(peerId, cb)
        Log.i(TAG, "SERVER: [Calling requestNetwork] for ${peerId.take(8)} with port $port")
        try {
            // use requestNetwork with a timeout to trigger onUnavailable if it fails
            bearer.cm.requestNetwork(req, cb, NETWORK_REQUEST_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "SERVER: ConnectivityManager.requestNetwork threw exception", e)
            connectionTracker.disconnect(peerId)
        }

        val readyId = (System.nanoTime() and 0x7fffffff).toInt()
        val readyPayload = buildServerReadyPayload(port)
        Handler(Looper.getMainLooper()).post {
            try {
                pubSession.sendMessage(peerHandle, readyId, readyPayload)
                Log.d(TAG, "PUBLISH: server-ready sent (msgId=$readyId, port=$port)")
            } catch (e: Exception) {
                Log.e(TAG, "PUBLISH: Exception sending server-ready to $peerHandle", e)
            }
        }
    }

    /**
     * Sends periodic TCP and discovery keep-alive messages to maintain a subscriber connection.
     */
    private fun handleSubscriberKeepAlive(
        client: SyncedSocket,
        peerId: String,
        pubSession: PublishDiscoverySession,
        peerHandle: PeerHandle
    ) {
        // TCP keep-alive pings
        bearer.scope.launch {
            try {
                while (bearer.connectionTracker.isConnected(peerId)) {
                    // write empty byte array effectively sends [4 bytes length=0] which is our ping
                    try {
                        client.write(ByteArray(0))
                    } catch (_: IOException) {
                        // The write side is dead. Don't just stop pinging: actively tear down so the
                        // half-open socket stops counting as "connected" and maintenance can retry.
                        handlePeerDisconnection(peerId, client)
                        break
                    }
                    delay(2_000)
                }
            } catch (_: Exception) {}
        }
        // Discovery keep-alive
        bearer.scope.launch {
            var msgId = 0
            while (bearer.connectionTracker.isConnected(peerId)) {
                try { pubSession.sendMessage(peerHandle, msgId++, ByteArray(0)) } catch (_: Exception) { break }
                delay(20_000)
            }
        }
    }

    private fun connectAwareClientSocket(
        network: Network,
        scopedAddr: Inet6Address,
        port: Int,
        peerId: String
    ): Socket {
        var lastFailure: IOException? = null
        for (attempt in 1..CLIENT_SOCKET_ATTEMPTS) {
            val delayMs = if (attempt == 1) CLIENT_SOCKET_READY_DELAY_MS else CLIENT_SOCKET_RETRY_DELAY_MS
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Interrupted before Wi-Fi Aware socket connect")
                }
            }

            var sock: Socket? = null
            try {
                sock = network.socketFactory.createSocket()
                sock.tcpNoDelay = true
                sock.keepAlive = true
                sock.connect(java.net.InetSocketAddress(scopedAddr, port), CLIENT_CONNECT_TIMEOUT_MS)
                if (attempt > 1) {
                    Log.i(TAG, "CLIENT: socket connect succeeded for ${peerId.take(8)} on attempt $attempt")
                }
                return sock
            } catch (e: IOException) {
                lastFailure = e
                try { sock?.close() } catch (_: Exception) { }
                if (attempt < CLIENT_SOCKET_ATTEMPTS) {
                    Log.w(TAG, "CLIENT: socket attempt $attempt/$CLIENT_SOCKET_ATTEMPTS failed for ${peerId.take(8)}: ${e.message}; retrying")
                }
            }
        }

        throw lastFailure ?: IOException("Wi-Fi Aware socket connect failed without an exception")
    }

    fun buildServerReadyPayload(port: Int): ByteArray {
        val peerIdBytes = bearer.myPeerID.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(Int.SIZE_BYTES + peerIdBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(port)
            .put(peerIdBytes)
            .array()
    }

    private fun peerIdFromServerReadyPayload(payload: ByteArray): String? {
        if (payload.size <= Int.SIZE_BYTES) return null
        val peerId = try {
            String(payload.copyOfRange(Int.SIZE_BYTES, payload.size), Charsets.UTF_8).trim()
        } catch (_: Exception) {
            return null
        }
        return peerId.takeIf { id ->
            id.length == 16 && id.all { ch -> ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F' }
        }?.lowercase()
    }

    private fun resolveServerReadyPeerId(peerHandle: PeerHandle, payload: ByteArray): String? {
        val advertisedPeerId = peerIdFromServerReadyPayload(payload)
        val mappedPeerId = bearer.handleToPeerId[peerHandle]?.takeIf { it.isNotBlank() }
        val peerId = advertisedPeerId ?: mappedPeerId
        if (peerId == null) {
            Log.w(TAG, "SUBSCRIBE: dropped server-ready with no peer mapping and no peer ID payload (payload=${payload.size}B)")
            return null
        }

        bearer.handleToPeerId[peerHandle] = peerId
        bearer.subscribeHandles[peerId] = peerHandle
        bearer.rememberDiscoveredPeer(peerId)
        if (advertisedPeerId != null && mappedPeerId != null && advertisedPeerId != mappedPeerId) {
            Log.d(TAG, "SUBSCRIBE: server-ready remapped handle ${mappedPeerId.take(8)} -> ${advertisedPeerId.take(8)}")
        }
        return peerId
    }

    /**
     * Handles a "server ready" message from a publishing peer and initiates a client connection.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    fun handleServerReady(
        peerHandle: PeerHandle,
        payload: ByteArray
    ) {
        if (payload.size < Int.SIZE_BYTES) {
            Log.w(TAG, "handleServerReady called with invalid payload size=${payload.size}, dropping")
            return
        }

        val peerId = resolveServerReadyPeerId(peerHandle, payload) ?: return
        if (peerId == bearer.myPeerID) return
        if (bearer.amIServerFor(peerId)) return
        val connectionTracker = bearer.connectionTracker
        if (connectionTracker.peerSockets.containsKey(peerId)) {
            Log.v(TAG, "↪ already client-connected to $peerId, skipping")
            return
        }
        val cancelledServerOffers = connectionTracker.cancelPendingServerDataPaths(peerId)
        if (cancelledServerOffers.isNotEmpty()) {
            val cancelled = cancelledServerOffers.joinToString(", ") { it.take(8) }
            Log.i(TAG, "CLIENT: preempted pending server offer(s) for $cancelled to connect ${peerId.take(8)}")
        }
        if (connectionTracker.hasPendingDataPathRequest(peerId)) {
            val pending = connectionTracker.pendingDataPathPeerIds(peerId).joinToString(", ") { it.take(8) }
            Log.d(TAG, "CLIENT: deferring server-ready for ${peerId.take(8)}; pending Aware data path(s): $pending")
            return
        }
        if (!connectionTracker.addPendingConnection(peerId)) {
            return
        }

        val port = ByteBuffer.wrap(payload, 0, Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).int
        Log.i(TAG, "CLIENT: Received server-ready from ${peerId.take(8)} on port $port (payload=${payload.size}B). Requesting network...")

        val subSession = bearer.subscribeSession ?: run {
            Log.w(TAG, "CLIENT: subscribe session missing for server-ready from ${peerId.take(8)}")
            connectionTracker.removePendingConnection(peerId)
            return
        }
        val spec = WifiAwareNetworkSpecifier.Builder(subSession, peerHandle)
            .setPskPassphrase(PSK)
            .build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(spec)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            @Volatile private var activeSocket: SyncedSocket? = null
            private val connectStarted = AtomicBoolean(false)

            override fun onAvailable(network: Network) {
                Log.i(TAG, "CLIENT: onAvailable() - Aware network is ready for ${peerId.take(8)}")
                // Do not bind process for Aware; use per-socket binding instead
            }

            override fun onUnavailable() {
                Log.e(TAG, "CLIENT: onUnavailable() - Failed to acquire Aware network for ${peerId.take(8)}")
                if (bearer.shouldRequestRoleReversalAfterClientFailure(peerId)) {
                    bearer.requestRoleReversal(peerId, allowForcedClientOverride = true)
                }
                handleNetworkFailure(peerId)
            }

            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) {
                if (connectionTracker.peerSockets.containsKey(peerId)) return
                val info = (nc.transportInfo as? WifiAwareNetworkInfo) ?: return
                val addr = info.peerIpv6Addr ?: return
                val connectPort = if (info.port > 0) info.port else port
                // onCapabilitiesChanged can fire multiple times; only connect once
                if (!connectStarted.compareAndSet(false, true)) return
                Log.i(TAG, "CLIENT: onCapabilitiesChanged() - Peer IPv6 discovered: $addr port=$connectPort")

                val lp = bearer.cm.getLinkProperties(network)
                val iface = lp?.interfaceName

                // Offload the blocking connect() off the callback thread.
                bearer.listenerExec.execute {
                    try {
                        // Use scoped IPv6 if interface name is available
                        val scopedAddr = if (iface != null && addr.scopeId == 0) {
                            try {
                                Inet6Address.getByAddress(null, addr.address, java.net.NetworkInterface.getByName(iface))
                            } catch (e: Exception) {
                                addr
                            }
                        } else {
                            addr
                        }

                        val sock = connectAwareClientSocket(network, scopedAddr, connectPort, peerId)
                        Log.i(TAG, "CLIENT: TCP connected to ${peerId.take(8)} at $scopedAddr:$connectPort")

                        val synced = SyncedSocket(sock)
                        activeSocket = synced
                        connectionTracker.onClientConnected(peerId, synced)
                        bearer.clientSocketFailures.remove(peerId)
                        onPeerSocketEstablished(peerId, inbound = false)
                        bearer.listenerExec.execute { listenToPeer(synced, peerId) }
                        handleServerKeepAlive(synced, peerId, peerHandle)
                    } catch (ioe: IOException) {
                        Log.e(TAG, "CLIENT: socket connect failed to ${peerId.take(8)}", ioe)
                        if (bearer.shouldRequestRoleReversalAfterClientFailure(peerId)) {
                            bearer.requestRoleReversal(peerId, allowForcedClientOverride = true)
                        }
                        handleNetworkFailure(peerId)
                    }
                }
            }

            override fun onLost(network: Network) {
                handlePeerDisconnection(peerId, activeSocket)
                Log.i(TAG, "CLIENT: WiFi Aware network lost for ${peerId.take(8)}")
            }
        }

        connectionTracker.addNetworkCallback(peerId, cb)
        Log.i(TAG, "CLIENT: [Calling requestNetwork] for ${peerId.take(8)}")
        try {
            bearer.cm.requestNetwork(req, cb, NETWORK_REQUEST_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "CLIENT: ConnectivityManager.requestNetwork threw exception", e)
            connectionTracker.disconnect(peerId)
        }
    }

    /**
     * Sends periodic TCP and discovery keep-alive messages for server connections.
     */
    private fun handleServerKeepAlive(
        sock: SyncedSocket,
        peerId: String,
        peerHandle: PeerHandle
    ) {
        // TCP keep-alive
        bearer.scope.launch {
            try {
                while (bearer.connectionTracker.isConnected(peerId)) {
                    try {
                        sock.write(ByteArray(0))
                    } catch (_: IOException) {
                        // The write side is dead. Tear down so the half-open socket stops counting
                        // as "connected" and maintenance can retry instead of silently stalling.
                        handlePeerDisconnection(peerId, sock)
                        break
                    }
                    delay(2_000.milliseconds)
                }
            } catch (_: Exception) {}
        }
        // Discovery keep-alive
        bearer.scope.launch {
            var msgId = 0
            while (bearer.connectionTracker.isConnected(peerId)) {
                try { bearer.subscribeSession?.sendMessage(peerHandle, msgId++, ByteArray(0)) } catch (_: Exception) { break }
                delay(20_000.milliseconds)
            }
        }
    }

    /**
     * Listens for incoming packets from a connected peer and emits them on the bearer's
     * incoming flow.
     *
     * @param socket Socket connected to the peer
     * @param initialLogicalPeerId Temporary identifier before peer ID resolution
     */
    private fun listenToPeer(socket: SyncedSocket, initialLogicalPeerId: String) {
        var logicalPeerId = initialLogicalPeerId
        while (bearer.isRadioActive) {
            val raw = socket.read() ?: break

            if (raw.isEmpty()) {
                // Keep-alive (0 length frame)
                continue
            }

            val pkt = BitchatPacket.fromBinaryData(raw) ?: continue

            val senderPeerHex = pkt.senderID.toHexString().take(16)

            if (pkt.type == MessageType.ANNOUNCE.value && pkt.ttl >= MAX_TTL && senderPeerHex != logicalPeerId) {
                val previousPeerId = logicalPeerId
                val wasInbound = bearer.neighborsState.value
                    .firstOrNull { it.peerID == previousPeerId }?.isInbound ?: bearer.amIServerFor(senderPeerHex)
                logicalPeerId = bearer.connectionTracker.rebindPeerId(previousPeerId, senderPeerHex, socket)
                bearer.handleToPeerId.forEach { (handle, peerId) ->
                    if (peerId == previousPeerId) {
                        bearer.handleToPeerId[handle] = senderPeerHex
                    }
                }
                bearer.subscribeHandles.remove(previousPeerId)?.let { bearer.subscribeHandles[senderPeerHex] = it }
                bearer.discoveredTimestamps.remove(previousPeerId)
                bearer.discoveredTimestamps[senderPeerHex] = System.currentTimeMillis()
                bearer.publishHandles.remove(previousPeerId)?.let { bearer.publishHandles[senderPeerHex] = it }
                onPeerRebound(previousPeerId, senderPeerHex, wasInbound)
                Log.i(TAG, "RX: rebound Wi-Fi direct peer ${previousPeerId.take(8)} -> ${senderPeerHex.take(8)}")
            }

            // Route the packet:
            // - peerID = Originator (who signed it)
            // - relayAddress = Neighbor link (who sent it to us over this socket)
            Log.d(TAG, "RX: packet type=${pkt.type} from ${senderPeerHex.take(8)} via ${logicalPeerId.take(8)} (bytes=${raw.size})")
            try {
                bearer.telemetry.logIncoming(
                    packet = pkt,
                    fromPeerID = senderPeerHex,
                    fromNickname = null,
                    fromDeviceAddress = bearer.linkAddressFor(logicalPeerId),
                    myPeerID = bearer.myPeerID,
                )
            } catch (_: Exception) { }
            bearer.incomingFlow.tryEmit(RoutedPacket(pkt, senderPeerHex, bearer.linkAddressFor(logicalPeerId)))
        }

        // Breaking out of the loop means the socket is dead or service is stopping.
        Log.i(TAG, "Socket loop terminated for ${logicalPeerId.take(8)} removing peer.")
        handlePeerDisconnection(logicalPeerId, socket)
        socket.close()
    }

    fun handleNetworkFailure(peerId: String) {
        bearer.scope.launch {
            Log.d(TAG, "Network failure cleanup for: $peerId")
            val connectionTracker = bearer.connectionTracker
            if (!connectionTracker.isConnected(peerId)) {
                val canonicalPeerId = connectionTracker.canonicalPeerId(peerId)
                connectionTracker.disconnect(peerId)
                onPeerLinkLost(canonicalPeerId)
                if (canonicalPeerId != peerId) {
                    onPeerLinkLost(peerId)
                }
            } else {
                Log.d(TAG, "Network failure ignored for $peerId - another socket is active")
            }
        }
    }

    fun handlePeerDisconnection(initialId: String, socket: SyncedSocket? = null) {
        bearer.scope.launch {
            // Check if this socket is the current active one before nuking the session
            val connectionTracker = bearer.connectionTracker
            val currentSocket = connectionTracker.getSocketForPeer(initialId)
            val canonicalPeerId = connectionTracker.canonicalPeerId(initialId)
            if (currentSocket === socket) {
                Log.d(TAG, "Cleaning up peer: $canonicalPeerId (active socket)")
                connectionTracker.disconnect(initialId)
                onPeerLinkLost(canonicalPeerId)
                if (canonicalPeerId != initialId) {
                    onPeerLinkLost(initialId)
                }
            } else if (socket == null && currentSocket == null) {
                // Fallback: If we don't have a specific socket context but we are already disconnected, ensure cleanup
                Log.d(TAG, "Cleaning up peer: $initialId (no active socket)")
                connectionTracker.disconnect(initialId)
                onPeerLinkLost(canonicalPeerId)
                if (canonicalPeerId != initialId) {
                    onPeerLinkLost(initialId)
                }
            } else {
                Log.d(TAG, "Ignored disconnection for $initialId - socket replaced or inactive")
                // Do not remove peer/session, as a new socket has likely taken over
            }
        }
    }
}
