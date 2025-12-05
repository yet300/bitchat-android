package com.bitchat.android.nostr

import android.app.Application
import android.util.Log
import com.bitchat.android.domain.event.ChatEventBus
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.services.SeenMessageStore
import jakarta.inject.Inject
import jakarta.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Date

/**
 * NostrDirectMessageHandler
 * - Processes gift-wrapped Nostr DMs
 * - Emits events to ChatEventBus (NOT directly to UI state)
 * 
 * Clean Architecture: This handler emits events, Store consumes them.
 */
@Singleton
class NostrDirectMessageHandler @Inject constructor(
    private val application: Application,
    private val repo: GeohashRepository,
    private val dataManager: com.bitchat.android.ui.DataManager,
    private val nostrTransport: NostrTransport,
    private val seenStore: SeenMessageStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    /**
     * Provider for the current user's nickname - set by the Store during initialization
     */
    var nicknameProvider: (() -> String?) = { null }
    
    companion object { private const val TAG = "NostrDirectMessageHandler" }

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

    fun onGiftWrap(giftWrap: NostrEvent, geohash: String, identity: NostrIdentity) {
        scope.launch(Dispatchers.Default) {
            try {
                if (dedupe(giftWrap.id)) return@launch

                val messageAge = System.currentTimeMillis() / 1000 - giftWrap.createdAt
                if (messageAge > 173700) return@launch // 48 hours + 15 mins

                val decryptResult = NostrProtocol.decryptPrivateMessage(giftWrap, identity)
                if (decryptResult == null) {
                    Log.w(TAG, "Failed to decrypt Nostr message")
                    return@launch
                }

                val (content, senderPubkey, rumorTimestamp) = decryptResult

                // Blocked users check
                if (dataManager.isGeohashUserBlocked(senderPubkey)) return@launch
                if (!content.startsWith("bitchat1:")) return@launch

                val base64Content = content.removePrefix("bitchat1:")
                val packetData = base64URLDecode(base64Content) ?: return@launch
                val packet = BitchatPacket.fromBinaryData(packetData) ?: return@launch

                if (packet.type != com.bitchat.android.protocol.MessageType.NOISE_ENCRYPTED.value) return@launch

                val noisePayload = com.bitchat.android.model.NoisePayload.decode(packet.payload) ?: return@launch
                val messageTimestamp = Date(giftWrap.createdAt * 1000L)
                val convKey = "nostr_${senderPubkey.take(16)}"
                
                // Update repository mappings
                repo.putNostrKeyMapping(convKey, senderPubkey)
                GeohashAliasRegistry.put(convKey, senderPubkey)
                
                if (geohash.isNotEmpty()) {
                    repo.setConversationGeohash(convKey, geohash)
                    GeohashConversationRegistry.set(convKey, geohash)
                    
                    // Ensure sender appears in geohash people list
                    val cached = repo.getCachedNickname(senderPubkey)
                    if (cached == null) {
                        val base = repo.displayNameForNostrPubkeyUI(senderPubkey).substringBefore("#")
                        repo.cacheNickname(senderPubkey, base)
                    }
                    repo.updateParticipant(geohash, senderPubkey, messageTimestamp)
                }

                val senderNickname = repo.displayNameForNostrPubkeyUI(senderPubkey)

                processNoisePayload(noisePayload, convKey, senderNickname, messageTimestamp, senderPubkey, identity)

            } catch (e: Exception) {
                Log.e(TAG, "onGiftWrap error: ${e.message}")
            }
        }
    }


    private suspend fun processNoisePayload(
        payload: com.bitchat.android.model.NoisePayload,
        convKey: String,
        senderNickname: String,
        timestamp: Date,
        senderPubkey: String,
        recipientIdentity: NostrIdentity
    ) {
        when (payload.type) {
            com.bitchat.android.model.NoisePayloadType.PRIVATE_MESSAGE -> {
                val pm = com.bitchat.android.model.PrivateMessagePacket.decode(payload.data) ?: return
                
                val myNickname = nicknameProvider() ?: "Unknown"
                val message = BitchatMessage(
                    id = pm.messageID,
                    sender = senderNickname,
                    content = pm.content,
                    timestamp = timestamp,
                    isRelay = false,
                    isPrivate = true,
                    recipientNickname = myNickname,
                    senderPeerID = convKey,
                    deliveryStatus = DeliveryStatus.Delivered(to = myNickname, at = Date())
                )

                val suppressUnread = seenStore.hasRead(pm.messageID)

                // Emit private message event (Store will handle state update)
                ChatEventBus.emitPrivateMessage(
                    ChatEventBus.PrivateMessageEvent(
                        conversationKey = convKey,
                        message = message,
                        suppressUnread = suppressUnread,
                        source = ChatEventBus.MessageSource.NOSTR_DM
                    )
                )

                // Send delivery ack if not already sent
                if (!seenStore.hasDelivered(pm.messageID)) {
                    nostrTransport.sendDeliveryAckGeohash(pm.messageID, senderPubkey, recipientIdentity)
                    seenStore.markDelivered(pm.messageID)
                }

                // Note: Read receipt logic moved to Store (it knows if user is viewing this chat)
            }
            
            com.bitchat.android.model.NoisePayloadType.DELIVERED -> {
                val messageId = String(payload.data, Charsets.UTF_8)
                
                // Emit delivery status event
                ChatEventBus.emitDeliveryStatus(
                    ChatEventBus.DeliveryStatusEvent(
                        messageId = messageId,
                        status = DeliveryStatus.Delivered(to = senderNickname, at = Date()),
                        conversationKey = convKey
                    )
                )
            }
            
            com.bitchat.android.model.NoisePayloadType.READ_RECEIPT -> {
                val messageId = String(payload.data, Charsets.UTF_8)
                
                // Emit read receipt event
                ChatEventBus.emitDeliveryStatus(
                    ChatEventBus.DeliveryStatusEvent(
                        messageId = messageId,
                        status = DeliveryStatus.Read(by = senderNickname, at = Date()),
                        conversationKey = convKey
                    )
                )
            }
            
            com.bitchat.android.model.NoisePayloadType.FILE_TRANSFER -> {
                val file = com.bitchat.android.model.BitchatFilePacket.decode(payload.data)
                if (file != null) {
                    val uniqueMsgId = java.util.UUID.randomUUID().toString().uppercase()
                    val savedPath = com.bitchat.android.features.file.FileUtils.saveIncomingFile(application, file)
                    val myNickname = nicknameProvider() ?: "Unknown"
                    
                    val message = BitchatMessage(
                        id = uniqueMsgId,
                        sender = senderNickname,
                        content = savedPath,
                        type = com.bitchat.android.features.file.FileUtils.messageTypeForMime(file.mimeType),
                        timestamp = timestamp,
                        isRelay = false,
                        isPrivate = true,
                        recipientNickname = myNickname,
                        senderPeerID = convKey
                    )
                    
                    Log.d(TAG, "📄 Saved Nostr encrypted incoming file to $savedPath (msgId=$uniqueMsgId)")
                    
                    // Emit file message event
                    ChatEventBus.emitPrivateMessage(
                        ChatEventBus.PrivateMessageEvent(
                            conversationKey = convKey,
                            message = message,
                            suppressUnread = false,
                            source = ChatEventBus.MessageSource.NOSTR_DM
                        )
                    )
                } else {
                    Log.w(TAG, "⚠️ Failed to decode Nostr file transfer from $convKey")
                }
            }
        }
    }

    private fun base64URLDecode(input: String): ByteArray? {
        return try {
            val padded = input.replace("-", "+")
                .replace("_", "/")
                .let { str ->
                    val padding = (4 - str.length % 4) % 4
                    str + "=".repeat(padding)
                }
            android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode base64url: ${e.message}")
            null
        }
    }
}
