package com.app.transport.prekey

import com.app.transport.protocol.BitchatPacket

/**
 * One-time prekey bundle events (BitchatPacket 0x24), surfaced as a narrow SPI so the platform-free
 * prekey coordinator owns bundle verification and the peer-bundle store without implementing the full
 * [BluetoothMeshDelegate][com.app.transport.mesh.BluetoothMeshDelegate]. Mirrors
 * [CourierEventListener][com.app.transport.courier.CourierEventListener].
 *
 * Unlike boards, the mesh does NOT verify the bundle before calling this: attribution is layered
 * (outer packet sender must be the bundle owner, and both the inner bundle signature and the outer
 * packet signature must verify against the owner's announce-bound signing key), and that lookup +
 * verification lives in the coordinator. Relay is not gated on it — a node that cannot verify a
 * bundle yet still spreads it, because bundles race the announces that bind their signing keys.
 */
interface PrekeyEventListener {

    /**
     * A broadcast 0x24 packet carrying a `PrekeyBundle` arrived from the mesh. The coordinator
     * decodes it, verifies attribution + signatures against the owner's announce-bound signing key,
     * and caches it for later forward-secret courier sealing.
     */
    fun onPrekeyBundleReceived(packet: BitchatPacket)

    /**
     * Opening a courier envelope v2 just retired one of our one-time prekeys, shrinking our
     * published bundle. The coordinator replenishes the batch if it ran low and re-publishes so
     * peers replace their cached copy and stop assigning the consumed id before the grace lapses.
     */
    fun onLocalPrekeyConsumed()
}
