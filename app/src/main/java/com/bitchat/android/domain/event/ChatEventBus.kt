package com.bitchat.android.domain.event

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Unified event bus for all chat-related events.
 * 
 * This decouples services (mesh, nostr) from UI state management.
 * Services emit events here, Stores subscribe and update state.
 * 
 * Pattern:
 *   Service → ChatEventBus.emit*() → Store subscribes → Reducer updates state
 * 
 * Benefits:
 * - Services don't need to know about ChatState or Stores
 * - Multiple consumers can react to same event
 * - Easy to add logging/debugging
 * - Testable (can observe emitted events)
 */
object ChatEventBus {

    // ═══════════════════════════════════════════════════════════════════════
    // PUBLIC MESSAGES (Mesh broadcast, Geohash channel messages)
    // ═══════════════════════════════════════════════════════════════════════
    
    private val _publicMessageReceived = MutableSharedFlow<PublicMessageEvent>(extraBufferCapacity = 64)
    val publicMessageReceived: SharedFlow<PublicMessageEvent> = _publicMessageReceived.asSharedFlow()
    
    suspend fun emitPublicMessage(event: PublicMessageEvent) {
        _publicMessageReceived.emit(event)
    }
    
    fun tryEmitPublicMessage(event: PublicMessageEvent) {
        _publicMessageReceived.tryEmit(event)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CHANNEL MESSAGES (Mesh channels, Geohash location channels)
    // ═══════════════════════════════════════════════════════════════════════
    
    private val _channelMessageReceived = MutableSharedFlow<ChannelMessageEvent>(extraBufferCapacity = 64)
    val channelMessageReceived: SharedFlow<ChannelMessageEvent> = _channelMessageReceived.asSharedFlow()
    
    suspend fun emitChannelMessage(event: ChannelMessageEvent) {
        _channelMessageReceived.emit(event)
    }
    
    fun tryEmitChannelMessage(event: ChannelMessageEvent) {
        _channelMessageReceived.tryEmit(event)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PRIVATE MESSAGES (Mesh DMs, Nostr DMs)
    // ═══════════════════════════════════════════════════════════════════════
    
    private val _privateMessageReceived = MutableSharedFlow<PrivateMessageEvent>(extraBufferCapacity = 64)
    val privateMessageReceived: SharedFlow<PrivateMessageEvent> = _privateMessageReceived.asSharedFlow()
    
    suspend fun emitPrivateMessage(event: PrivateMessageEvent) {
        _privateMessageReceived.emit(event)
    }
    
    fun tryEmitPrivateMessage(event: PrivateMessageEvent) {
        _privateMessageReceived.tryEmit(event)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DELIVERY STATUS (Acks, Read receipts)
    // ═══════════════════════════════════════════════════════════════════════
    
    private val _deliveryStatusUpdated = MutableSharedFlow<DeliveryStatusEvent>(extraBufferCapacity = 32)
    val deliveryStatusUpdated: SharedFlow<DeliveryStatusEvent> = _deliveryStatusUpdated.asSharedFlow()
    
    suspend fun emitDeliveryStatus(event: DeliveryStatusEvent) {
        _deliveryStatusUpdated.emit(event)
    }
    
    fun tryEmitDeliveryStatus(event: DeliveryStatusEvent) {
        _deliveryStatusUpdated.tryEmit(event)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GEOHASH PARTICIPANT UPDATES
    // ═══════════════════════════════════════════════════════════════════════
    
    private val _geohashParticipantUpdated = MutableSharedFlow<GeohashParticipantEvent>(extraBufferCapacity = 32)
    val geohashParticipantUpdated: SharedFlow<GeohashParticipantEvent> = _geohashParticipantUpdated.asSharedFlow()
    
    suspend fun emitGeohashParticipant(event: GeohashParticipantEvent) {
        _geohashParticipantUpdated.emit(event)
    }
    
    fun tryEmitGeohashParticipant(event: GeohashParticipantEvent) {
        _geohashParticipantUpdated.tryEmit(event)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // EVENT DATA CLASSES
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Public message received (mesh broadcast or geohash channel)
     */
    data class PublicMessageEvent(
        val message: BitchatMessage,
        val source: MessageSource
    )
    
    /**
     * Channel message received
     */
    data class ChannelMessageEvent(
        val channel: String,      // e.g., "#general" or "geo:u4pruydqqvj"
        val message: BitchatMessage,
        val source: MessageSource
    )
    
    /**
     * Private message received
     */
    data class PrivateMessageEvent(
        val conversationKey: String,  // peerID or "nostr_<pubkey>"
        val message: BitchatMessage,
        val suppressUnread: Boolean = false,
        val source: MessageSource
    )
    
    /**
     * Delivery status update (ack or read receipt)
     */
    data class DeliveryStatusEvent(
        val messageId: String,
        val status: DeliveryStatus,
        val conversationKey: String? = null
    )
    
    /**
     * Geohash participant update
     */
    data class GeohashParticipantEvent(
        val geohash: String,
        val pubkey: String,
        val nickname: String?,
        val isTeleported: Boolean = false
    )
    
    /**
     * Source of the message (for debugging/logging)
     */
    enum class MessageSource {
        MESH,           // Bluetooth mesh
        NOSTR_CHANNEL,  // Nostr geohash channel (kind 20000)
        NOSTR_DM        // Nostr gift-wrapped DM
    }
}
