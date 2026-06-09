package com.app.data.routing

import android.content.Context
import com.app.data.favorites.FavoritesPersistenceService
import com.app.data.nostr.NostrTransport
import com.app.transport.model.ReadReceipt
import com.app.transport.nostr.GeohashAliasRegistry
import com.app.transport.nostr.GeohashConversationRegistry
import com.app.transport.nostr.NostrIdentityBridge
import com.app.transport.routing.OutgoingEnvelope
import com.app.transport.routing.Reachability
import com.app.transport.routing.RouteStrategy
import com.app.transport.routing.SendOutcome
import dev.zacsweers.metro.Inject

/**
 * Routes messages via Nostr relay when mesh is unavailable.
 * Handles both regular Nostr DMs (mutual favorites with npub mapping)
 * and geohash-alias conversations.
 * Priority 50 — used only when mesh is unreachable.
 *
 * §6 Bearer SPI / Tier-2 RouteStrategy.
 */
@Inject
internal class NostrRouteStrategy(
    private val nostr: NostrTransport,
    private val favoritesService: FavoritesPersistenceService,
    private val geohashAliasRegistry: GeohashAliasRegistry,
    private val geohashConversationRegistry: GeohashConversationRegistry,
    private val context: Context,
) : RouteStrategy {

    override val priority = 50

    override fun reachability(peerID: String): Reachability {
        if (geohashAliasRegistry.contains(peerID)) return Reachability.ViaRelay
        return if (canSendViaNostr(peerID)) Reachability.ViaRelay else Reachability.Unreachable
    }

    override fun send(envelope: OutgoingEnvelope): SendOutcome {
        return when (envelope) {
            is OutgoingEnvelope.Private -> {
                if (geohashAliasRegistry.contains(envelope.peerID)) {
                    val recipientHex = geohashAliasRegistry.get(envelope.peerID)
                        ?: return SendOutcome.Failed("no hex for geohash alias ${envelope.peerID}")
                    val sourceGeohash = geohashConversationRegistry.get(envelope.peerID)
                    nostr.sendPrivateMessageGeohash(
                        envelope.content, recipientHex, envelope.messageId, sourceGeohash,
                    )
                } else {
                    nostr.sendPrivateMessage(
                        envelope.content, envelope.peerID, envelope.recipientNickname, envelope.messageId,
                    )
                }
                SendOutcome.Sent
            }
            is OutgoingEnvelope.Receipt -> {
                nostr.sendReadReceipt(ReadReceipt(envelope.originalMessageId), envelope.peerID)
                SendOutcome.Sent
            }
            is OutgoingEnvelope.Ack -> {
                if (geohashAliasRegistry.contains(envelope.peerID)) {
                    val recipientHex = geohashAliasRegistry.get(envelope.peerID)
                        ?: return SendOutcome.Failed("no hex for geohash alias ${envelope.peerID}")
                    val identity = try {
                        NostrIdentityBridge.getCurrentNostrIdentity(context)
                            ?: return SendOutcome.Failed("no Nostr identity")
                    } catch (_: Exception) {
                        return SendOutcome.Failed("identity error")
                    }
                    nostr.sendDeliveryAckGeohash(envelope.messageId, recipientHex, identity)
                } else {
                    nostr.sendDeliveryAck(envelope.messageId, envelope.peerID)
                }
                SendOutcome.Sent
            }
            is OutgoingEnvelope.Favorite -> {
                nostr.sendFavoriteNotification(envelope.peerID, envelope.isFavorite)
                SendOutcome.Sent
            }
        }
    }

    /** Mirrors [com.app.data.routing.MessageRouter.canSendViaNostr] exactly. */
    private fun canSendViaNostr(peerID: String): Boolean = try {
        val hexRegex = Regex("^[0-9a-fA-F]+$")
        when (peerID.length) {
            64 if peerID.matches(hexRegex) -> {
                val noiseKey = hexToBytes(peerID)
                val fav = favoritesService.getFavoriteStatus(noiseKey)
                fav?.isMutual == true && fav.peerNostrPublicKey != null
            }
            16 if peerID.matches(hexRegex) -> {
                val fav = favoritesService.getFavoriteStatus(peerID)
                fav?.isMutual == true && fav.peerNostrPublicKey != null
            }
            else -> false
        }
    } catch (_: Exception) { false }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = if (hex.length % 2 == 0) hex else "0$hex"
        return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
