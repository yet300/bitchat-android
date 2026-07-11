package com.app.transport.board

/**
 * Geohash board events (BitchatPacket 0x23), surfaced as a narrow SPI so the platform-free board
 * coordinator owns persistence + retention without the full
 * [BluetoothMeshDelegate][com.app.transport.mesh.BluetoothMeshDelegate]. Mirrors
 * [CourierEventListener][com.app.transport.courier.CourierEventListener].
 *
 * The mesh has already **decoded and signature-verified** the payload before calling this (the
 * verify gates the relay decision), so the listener may ingest it directly without re-verifying.
 */
interface BoardEventListener {

    /** A decoded, signature-verified `BoardWire` payload (post or tombstone) arrived from the mesh. */
    fun onBoardPacketReceived(payload: ByteArray)
}
