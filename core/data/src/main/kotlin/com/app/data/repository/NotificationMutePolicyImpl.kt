package com.app.data.repository

import com.app.common.settings.SettingsStore
import com.app.domain.model.ConversationId
import com.app.domain.model.PeerId
import com.app.domain.repository.NotificationMutePolicy
import com.app.transport.mesh.BluetoothMeshService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.SingleIn

/**
 * Reads the same persisted mute state the UI writes ([MutePrefsKeys]) — synchronously, on demand —
 * so the notifier and the conversation-list mute can never drift.
 *
 * The live notify path carries the ephemeral mesh senderPeerID, but pin/mute is stored under the
 * stable Noise key (see I12-2). [isPrivateMuted] therefore canonicalizes the sender mesh peerID to
 * its Noise key the same way the peer repository does ([BluetoothMeshService.getPeerInfo] →
 * noisePublicKey hex) before testing the muted set — and also checks the raw id, so a legacy
 * mesh-keyed entry from this session still matches. This keeps suppression cross-session stable.
 *
 * The mesh is injected as a [Provider] to break the construction cycle (BMS → ServiceNotifier →
 * NotificationMutePolicy); it is only resolved lazily on the on-demand suppression check.
 */
@SingleIn(AppScope::class)
@Inject
internal class NotificationMutePolicyImpl(
    private val settings: SettingsStore,
    private val mesh: Provider<BluetoothMeshService>,
) : NotificationMutePolicy {

    override fun isAllMuted(): Boolean = settings.getBoolean(MutePrefsKeys.GLOBAL_MUTE, false)

    override fun isPrivateMuted(peerId: String): Boolean {
        val muted = mutedSet()
        if (ConversationId.Private(PeerId(peerId)) in muted) return true
        val noiseHex = canonicalNoiseHex(peerId) ?: return false
        return ConversationId.Private(PeerId(noiseHex)) in muted
    }

    /** Stable Noise key hex for a sender peerID (already-stable id stays; mesh id resolves via BMS). */
    private fun canonicalNoiseHex(peerId: String): String? =
        if (PeerId(peerId).kind == PeerId.Kind.NOISE_STABLE) {
            peerId.lowercase()
        } else {
            mesh().getPeerInfo(peerId)?.noisePublicKey?.toHex()
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    override fun isGeohashMuted(geohash: String): Boolean =
        mutedSet().any { it is ConversationId.Geohash && it.channel.geohash == geohash }

    private fun mutedSet(): Set<ConversationId> =
        MutePrefsKeys.decodeSet(settings.getString(MutePrefsKeys.MUTED, ""))
}
