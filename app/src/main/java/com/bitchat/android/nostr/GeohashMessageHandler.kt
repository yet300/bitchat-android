package com.bitchat.android.nostr

import android.app.Application
import android.util.Log
import com.bitchat.android.domain.event.ChatEventBus
import com.bitchat.android.model.BitchatMessage
import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Date

/**
 * GeohashMessageHandler
 * - Processes kind=20000 Nostr events for geohash channels
 * - Updates repository for participants + nicknames
 * - Emits messages to MessageManager
 */
@Singleton
class GeohashMessageHandler @Inject constructor(
    private val application: Application,
    private val repo: GeohashRepository,
    private val dataManager: com.bitchat.android.ui.DataManager,
    private val powPreferenceManager: PoWPreferenceManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    companion object { private const val TAG = "GeohashMessageHandler" }

    // Simple event deduplication
    private val processedIds = ArrayDeque<String>()
    private val seen = HashSet<String>()
    private val max = 2000

    private fun dedupe(id: String): Boolean {
        if (seen.contains(id)) return true
        seen.add(id)
        processedIds.addLast(id)
        if (processedIds.size > max) {
            val old = processedIds.removeFirst()
            seen.remove(old)
        }
        return false
    }

    fun onEvent(event: NostrEvent, subscribedGeohash: String) {
        scope.launch(Dispatchers.Default) {
            try {
                Log.d(TAG, "📥 onEvent: kind=${event.kind}, geohash=$subscribedGeohash, pubkey=${event.pubkey.take(8)}")
                if (event.kind != 20000) return@launch
                val tagGeo = event.tags.firstOrNull { it.size >= 2 && it[0] == "g" }?.getOrNull(1)
                if (tagGeo == null || !tagGeo.equals(subscribedGeohash, true)) return@launch
                if (dedupe(event.id)) return@launch
                Log.d(TAG, "📥 Processing event id=${event.id.take(8)} for geohash=$subscribedGeohash")

                // PoW validation (if enabled)
                val pow = powPreferenceManager.getCurrentSettings()
                if (pow.enabled && pow.difficulty > 0) {
                    if (!NostrProofOfWork.validateDifficulty(event, pow.difficulty)) return@launch
                }

                // Blocked users check
                if (dataManager.isGeohashUserBlocked(event.pubkey)) return@launch

                // Update repository (participants, nickname, teleport)
                Log.d(TAG, "📥 Updating participant: geohash=$subscribedGeohash, pubkey=${event.pubkey.take(8)}")
                repo.updateParticipant(subscribedGeohash, event.pubkey, Date(event.createdAt * 1000L))

                val nickname = event.tags.find { it.size >= 2 && it[0] == "n" }?.getOrNull(1)
                nickname?.let { repo.cacheNickname(event.pubkey, it) }
                
                val isTeleported = event.tags.any { it.size >= 2 && it[0] == "t" && it[1] == "teleport" }
                if (isTeleported) {
                    repo.markTeleported(event.pubkey)
                }
                
                // Register geohash DM alias
                try {
                    GeohashAliasRegistry.put("nostr_${event.pubkey.take(16)}", event.pubkey)
                } catch (_: Exception) { }

                // Emit participant update event
                ChatEventBus.tryEmitGeohashParticipant(
                    ChatEventBus.GeohashParticipantEvent(
                        geohash = subscribedGeohash,
                        pubkey = event.pubkey,
                        nickname = nickname,
                        isTeleported = isTeleported
                    )
                )

                // Skip our own events for message emission
                val my = NostrIdentityBridge.deriveIdentity(subscribedGeohash, application)
                if (my.publicKeyHex.equals(event.pubkey, true)) return@launch

                // Skip teleport presence (empty content with teleport tag)
                val isTeleportPresence = isTeleported && event.content.trim().isEmpty()
                if (isTeleportPresence) return@launch

                // Build message
                val senderName = repo.displayNameForNostrPubkeyUI(event.pubkey)
                val hasNonce = try { NostrProofOfWork.hasNonce(event) } catch (_: Exception) { false }
                val msg = BitchatMessage(
                    id = event.id,
                    sender = senderName,
                    content = event.content,
                    timestamp = Date(event.createdAt * 1000L),
                    isRelay = false,
                    originalSender = repo.displayNameForNostrPubkey(event.pubkey),
                    senderPeerID = "nostr:${event.pubkey.take(8)}",
                    mentions = null,
                    channel = "#$subscribedGeohash",
                    powDifficulty = try {
                        if (hasNonce) NostrProofOfWork.calculateDifficulty(event.id).takeIf { it > 0 } else null
                    } catch (_: Exception) { null }
                )
                
                // Emit channel message event (Store will handle state update)
                ChatEventBus.tryEmitChannelMessage(
                    ChatEventBus.ChannelMessageEvent(
                        channel = "geo:$subscribedGeohash",
                        message = msg,
                        source = ChatEventBus.MessageSource.NOSTR_CHANNEL
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "onEvent error: ${e.message}")
            }
        }
    }
}
