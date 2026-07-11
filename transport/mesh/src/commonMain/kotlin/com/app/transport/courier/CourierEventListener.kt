package com.app.transport.courier

import com.app.transport.protocol.BitchatPacket

/**
 * Courier store-and-forward events (BitchatPacket 0x04), surfaced as a narrow SPI so the platform-free
 * courier coordinator can own the trust policy and carried-mail store without implementing the full
 * [BluetoothMeshDelegate][com.app.transport.mesh.BluetoothMeshDelegate]. Mirrors
 * [VouchEventListener][com.app.transport.vouch.VouchEventListener].
 *
 * The mesh opens envelopes addressed to *us* itself (delivering the private message like any other),
 * so this listener only sees the courier role: a deposit to carry for someone else, and the moment a
 * peer becomes reachable (verified announce) to hand carried mail over.
 */
interface CourierEventListener {

    /**
     * A directed 0x04 whose rotating tag is not ours arrived from [fromPeerID] — a trusted peer asking
     * us to carry mail for an offline third party. The listener authenticates the depositor (packet
     * signature + favorite/verified policy) before storing.
     */
    fun onCourierDeposit(fromPeerID: String, packet: BitchatPacket)

    /**
     * A signature-verified announce told us where [peerID] is: hand over any carried mail addressed to
     * them (direct = destructive handover + spray; relayed = speculative multi-hop flood).
     */
    fun onCourierPeerAvailable(peerID: String)
}
