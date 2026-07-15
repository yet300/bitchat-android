package com.app.transport.mesh

/**
 * Decides which central-role (client) connections are redundant duplicates of a peer's live link.
 *
 * Port of iOS `Services/BLE/BLERedundantLinkPolicy.swift`.
 *
 * One connection per role per peer is the normal dual-role topology (each device is both central and
 * peripheral). After BLE state restoration the same phone can reappear under a fresh peripheral
 * address while a restored connection lives on — leaving several live central-role connections to
 * one peer, each carrying every packet (field-verified: 2–3× airtime). Only same-role (client)
 * duplicates are retired; the peer's server-role subscription on our peripheral manager is its own
 * connection to manage.
 */
object BleRedundantLinkPolicy {

    data class PeripheralLink(
        val uuid: String,
        val peerID: String?,
        val isConnected: Boolean,
        /**
         * Whether the link has a discovered characteristic (is writable).
         * A link mid-service-rediscovery must never be kept over a writable duplicate.
         */
        val hasCharacteristic: Boolean,
    )

    /**
     * The link to keep when a peer has several connected bound client links, or null when there is
     * nothing to consolidate. Prefers the ingress link of the verified direct announce that
     * triggered the check (the strongest liveness proof available), falling back to the peer's most
     * recently bound link — but only among writable links while any exist. When neither anchor is a
     * viable candidate, consolidation waits for a later announce rather than guessing.
     */
    fun keptPeripheralUUID(
        ingressPeripheralUUID: String?,
        mostRecentlyBoundUUID: String?,
        links: List<PeripheralLink>,
        peerID: String,
    ): String? {
        val bound = links.filter { it.peerID == peerID && it.isConnected }
        if (bound.size <= 1) return null

        val writable = bound.filter { it.hasCharacteristic }
        val candidates = if (writable.isEmpty()) bound else writable

        if (ingressPeripheralUUID != null && candidates.any { it.uuid == ingressPeripheralUUID }) {
            return ingressPeripheralUUID
        }
        if (mostRecentlyBoundUUID != null && candidates.any { it.uuid == mostRecentlyBoundUUID }) {
            return mostRecentlyBoundUUID
        }
        return null
    }

    /** Connected client links bound to [peerID] other than the kept one. */
    fun peripheralUUIDsToRetire(
        links: List<PeripheralLink>,
        peerID: String,
        keeping: String,
    ): List<String> =
        links
            .filter { it.peerID == peerID && it.isConnected && it.uuid != keeping }
            .map { it.uuid }
}

/**
 * Snapshot of one central-role (client) link for [BleRedundantLinkPolicy].
 * Addresses are opaque platform strings (Android MAC / iOS NSUUID).
 */
data class BleClientLinkSnapshot(
    val address: String,
    val peerID: String?,
    val isConnected: Boolean,
    val hasCharacteristic: Boolean,
) {
    fun toPolicyLink(): BleRedundantLinkPolicy.PeripheralLink =
        BleRedundantLinkPolicy.PeripheralLink(
            uuid = address,
            peerID = peerID,
            isConnected = isConnected,
            hasCharacteristic = hasCharacteristic,
        )
}
