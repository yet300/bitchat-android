package com.bitchat.android.core.domain.usecase

import com.bitchat.android.core.domain.model.PeerId
import com.bitchat.android.core.domain.repository.ContactRepository
import com.bitchat.android.core.domain.repository.PeerRepository

/**
 * Резолв канонического адреса собеседника (порт логики ConversationAliasResolver).
 *
 * Правило: для стабильного Noise-ключа или Nostr-алиаса, если этот же человек СЕЙЧАС онлайн
 * по mesh — предпочитаем живой mesh-peerID (туда дешевле и быстрее доставлять). Иначе
 * оставляем исходный адрес.
 */
class ResolveConversationUseCase(
    private val peers: PeerRepository,
    private val contacts: ContactRepository,
) {

    suspend operator fun invoke(target: PeerId): PeerId = when (target.kind) {
        PeerId.Kind.MESH_EPHEMERAL -> target
        PeerId.Kind.NOISE_STABLE -> resolveByNoiseHex(target.raw) ?: target
        PeerId.Kind.NOSTR_ALIAS -> {
            val noiseHex = contacts.noiseKeyHexForNostrAlias(target) ?: return target
            resolveByNoiseHex(noiseHex) ?: target
        }
    }

    private suspend fun resolveByNoiseHex(noiseHex: String): PeerId? =
        peers.snapshot()
            .firstOrNull { it.isConnected && it.noiseKeyHex.equals(noiseHex, ignoreCase = true) }
            ?.id
}
