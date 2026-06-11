package com.app.data.routing

import com.app.common.encoding.hexEncodedString
import com.app.data.favorites.FavoritesPersistenceService
import com.app.domain.model.PeerId
import com.app.transport.mesh.BluetoothMeshService
import com.app.transport.nostr.Bech32
import com.app.transport.nostr.GeohashAliasRegistry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Single owner of peer-address resolution (audit B5). Replaces three divergent copies of
 * the same logic: ConversationAliasResolver (callback soup in :app),
 * ContactRepositoryImpl.noiseKeyHexForNostrAlias and NostrRouteStrategy.canSendViaNostr's
 * length/regex dispatch — peer-kind classification now goes through [PeerId.kind].
 *
 * Address vocabulary: mesh ephemeral 16-hex peerID, stable 64-hex Noise key,
 * `nostr_<pub16>` geohash alias.
 */
@SingleIn(AppScope::class)
@Inject
class PeerAddressResolver(
    private val mesh: BluetoothMeshService,
    private val geohashAliasRegistry: GeohashAliasRegistry,
    private val favorites: FavoritesPersistenceService,
) {

    /**
     * Resolve a `nostr_<pub16>` alias to the full Nostr pubkey hex: the geohash alias
     * registry first, then a best-effort prefix match over our favorites' npub mappings
     * (mesh-favorite aliases never enter the registry).
     */
    fun nostrPubkeyHexForAlias(alias: String): String? {
        if (PeerId(alias).kind != PeerId.Kind.NOSTR_ALIAS) return null
        if (geohashAliasRegistry.contains(alias)) return geohashAliasRegistry.get(alias)
        val prefix = alias.removePrefix("nostr_")
        val favs = try { favorites.getOurFavorites() } catch (_: Exception) { return null }
        return favs.firstNotNullOfOrNull { rel ->
            rel.peerNostrPublicKey?.let { npub ->
                runCatching { Bech32.decode(npub) }.getOrNull()?.let { decoded ->
                    if (decoded.first == "npub") decoded.second.hexEncodedString() else null
                }
            }
        }?.takeIf { it.startsWith(prefix, ignoreCase = true) }
    }

    /** Bridge a `nostr_` alias to the peer's stable Noise key hex via favorites. */
    fun noiseKeyHexForNostrAlias(alias: String): String? {
        val pubkeyHex = nostrPubkeyHexForAlias(alias) ?: return null
        val noiseKey = runCatching { favorites.findNoiseKey(pubkeyHex) }.getOrNull() ?: return null
        return noiseKey.hexEncodedString()
    }

    /**
     * Whether a Nostr DM route exists for [peerID]: a mutual favorite with a known npub
     * mapping. Dispatches on [PeerId.kind] instead of the legacy length/regex checks.
     */
    fun canSendViaNostr(peerID: String): Boolean = try {
        when (PeerId(peerID).kind) {
            PeerId.Kind.NOISE_STABLE -> {
                val fav = favorites.getFavoriteStatus(peerID.hexToBytes())
                fav?.isMutual == true && fav.peerNostrPublicKey != null
            }
            PeerId.Kind.MESH_EPHEMERAL -> {
                val fav = favorites.getFavoriteStatus(peerID)
                fav?.isMutual == true && fav.peerNostrPublicKey != null
            }
            else -> false
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Canonical conversation target for [selectedPeerID]:
     * - `nostr_` alias → the connected mesh peer holding the bridged Noise key, else the
     *   stable Noise hex, else the alias itself;
     * - stable 64-hex Noise key → upgraded to the connected mesh peer if one matches;
     * - anything else (mesh ephemeral) → unchanged.
     */
    fun canonicalPeerID(selectedPeerID: String, connectedPeers: List<String>): String {
        try {
            when (PeerId(selectedPeerID).kind) {
                PeerId.Kind.NOSTR_ALIAS -> {
                    val noiseHex = noiseKeyHexForNostrAlias(selectedPeerID) ?: return selectedPeerID
                    val noiseKey = noiseHex.hexToBytes()
                    return connectedMeshPeerFor(noiseKey, connectedPeers) ?: noiseHex
                }
                PeerId.Kind.NOISE_STABLE -> {
                    val noiseKey = selectedPeerID.hexToBytes()
                    return connectedMeshPeerFor(noiseKey, connectedPeers) ?: selectedPeerID
                }
                else -> return selectedPeerID
            }
        } catch (_: Exception) {
            return selectedPeerID
        }
    }

    private fun connectedMeshPeerFor(noiseKey: ByteArray, connectedPeers: List<String>): String? =
        connectedPeers.firstOrNull { pid ->
            mesh.getPeerInfo(pid)?.noisePublicKey?.contentEquals(noiseKey) == true
        }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
