package com.app.data.session

import com.app.domain.model.PeerId
import com.app.domain.repository.NoiseSessionPort
import com.app.transport.mesh.BluetoothMeshService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/** [NoiseSessionPort] over the BLE mesh service. Pure delegation; orchestration lives in the domain. */
@SingleIn(AppScope::class)
@Inject
internal class MeshNoiseSessionPort(
    private val mesh: BluetoothMeshService,
) : NoiseSessionPort {

    override fun hasSession(peerId: PeerId): Boolean = mesh.hasEstablishedSession(peerId.raw)

    override fun myPeerId(): String = mesh.myPeerID

    override fun initiateHandshake(peerId: PeerId) = mesh.initiateNoiseHandshake(peerId.raw)

    override fun announceTo(peerId: PeerId) = mesh.sendAnnouncementToPeer(peerId.raw)
}
