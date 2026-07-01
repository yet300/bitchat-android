@file:OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)

package com.app.data.nostr

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import com.app.common.utils.Log
import com.app.data.favorites.FavoritesPersistenceService
import com.app.domain.model.ConversationId
import com.app.domain.repository.GeohashRepository
import com.app.transport.IncomingMessageSink
import com.app.transport.SeenMessageStore
import com.app.transport.features.file.IncomingFileStore
import com.app.transport.model.BitchatFilePacket
import com.app.transport.model.BitchatMessage
import com.app.transport.model.messageTypeForMime
import com.app.transport.model.DeliveryStatus
import com.app.transport.model.NoisePayloadType
import com.app.transport.nostr.GeohashAliasRegistry
import com.app.transport.nostr.GeohashConversationRegistry
import com.app.transport.nostr.NostrEmbeddedBitChat
import com.app.transport.nostr.NostrEvent
import com.app.transport.nostr.NostrFilter
import com.app.transport.nostr.NostrIdentity
import com.app.transport.nostr.NostrIdentityBridge
import com.app.transport.nostr.NostrProtocol
import com.app.transport.nostr.NostrRelayManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Receive side of Nostr direct messages (NIP-17 gift wraps): the path that Phase D deleted with
 * `NostrDirectMessageHandler`. Subscribes to gift wraps for the account identity (mesh-favorite DMs)
 * and for the active per-geohash ephemeral identity (geohash DMs), decrypts each rumor, decodes the
 * embedded BitChat packet and routes it:
 *   - chat message     -> [IncomingMessageSink.addPrivateMessage] under the `nostr_<pub16>` alias;
 *   - delivery/read ack -> the status sink ([IncomingMessageSink.onDeliveryAck]/[onReadReceipt]);
 *   - [FAVORITED]/[UNFAVORITED] -> [FavoritesPersistenceService] (mutual-favorite reachability).
 *
 * Mirrors the deleted handler's behaviour; the dead `NostrClient` subscribe path it duplicated was
 * removed so there is one ingest implementation.
 */
@SingleIn(AppScope::class)
@Inject
class NostrDirectMessageIngest(
    private val incomingFileStore: IncomingFileStore,
    private val relayManager: NostrRelayManager,
    private val nostrIdentityBridge: NostrIdentityBridge,
    private val sink: IncomingMessageSink,
    private val seenMessageStore: SeenMessageStore,
    private val favoritesService: FavoritesPersistenceService,
    private val nostrMessageSender: NostrMessageSender,
    private val geohashRepository: GeohashRepository,
    private val aliasRegistry: GeohashAliasRegistry,
    private val conversationRegistry: GeohashConversationRegistry,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val TAG = "NostrDirectMessageIngest"
        private const val DM_LOOKBACK_MS = 172_800_000L // 48h (align with NIP-17 timestamp randomization)
        private const val MAX_AGE_SECONDS = 173_700L     // 48h + 15min buffer
        private const val ACCOUNT_SUB_ID = "dm-account"
    }

    private var started = false
    private var accountSubId: String? = null
    private var geoSubId: String? = null
    private var geoSubGeohash: String? = null

    private val startLock = Lock()

    // Event-id dedup across all subscriptions (bounded, mirrors the legacy handler).
    private val seenLock = Lock()
    private val seenOrder = ArrayDeque<String>()
    private val seenSet = HashSet<String>()

    /** Connect default relays and start the account + geohash gift-wrap subscriptions. Idempotent. */
    fun start() {
        startLock.withLock {
            if (started) return
            started = true
        }

        // Ensure the default relays are connected so account-DM gift wraps arrive even with Tor off
        // and no geo channel open (the favorite-DM send path also queues to these relays).
        runCatching { relayManager.connect() }.onFailure { Log.e(TAG, "relay connect failed: ${it.message}") }

        subscribeAccount()

        // Follow the selected geohash so per-geohash DMs are received only while that channel is active.
        geohashRepository.observeSelectedChannel()
            .distinctUntilChanged()
            .onEach { updateGeohashSubscription(it) }
            .launchIn(scope)
    }

    private fun subscribeAccount() {
        val identity = runCatching { nostrIdentityBridge.getCurrentNostrIdentity() }.getOrNull() ?: run {
            Log.w(TAG, "No account Nostr identity; account DM subscription skipped")
            return
        }
        accountSubId = ACCOUNT_SUB_ID
        runCatching {
            relayManager.subscribe(
                filter = NostrFilter.giftWrapsFor(identity.publicKeyHex, Clock.System.now().toEpochMilliseconds() - DM_LOOKBACK_MS),
                id = ACCOUNT_SUB_ID,
                handler = { event -> onGiftWrap(event, geohash = "", identity = identity) },
            )
        }.onFailure { Log.e(TAG, "account subscription failed: ${it.message}") }
    }

    private fun updateGeohashSubscription(selected: ConversationId) {
        val geohash = (selected as? ConversationId.Geohash)?.channel?.geohash
        if (geohash == geoSubGeohash) return

        geoSubId?.let { relayManager.unsubscribe(it) }
        geoSubId = null
        geoSubGeohash = null

        if (geohash == null) return
        val identity = runCatching { nostrIdentityBridge.deriveIdentity(geohash) }.getOrNull() ?: return
        val id = "dm-geo-$geohash"
        geoSubId = id
        geoSubGeohash = geohash
        runCatching {
            relayManager.subscribe(
                filter = NostrFilter.giftWrapsFor(identity.publicKeyHex, Clock.System.now().toEpochMilliseconds() - DM_LOOKBACK_MS),
                id = id,
                handler = { event -> onGiftWrap(event, geohash = geohash, identity = identity) },
            )
        }.onFailure { Log.e(TAG, "geohash subscription failed for $geohash: ${it.message}") }
    }

    /** Process one incoming gift wrap received for [identity] (geohash="" for the account identity). */
    fun onGiftWrap(giftWrap: NostrEvent, geohash: String, identity: NostrIdentity) {
        scope.launch {
            try {
                if (dedupe(giftWrap.id)) return@launch
                val age = Clock.System.now().toEpochMilliseconds() / 1000 - giftWrap.createdAt
                if (age > MAX_AGE_SECONDS) return@launch

                val (content, rawSenderPubkey, _) = NostrProtocol.decryptPrivateMessage(giftWrap, identity)
                    ?: run { Log.w(TAG, "Failed to decrypt Nostr DM"); return@launch }
                // Normalize to lowercase so conversation/alias keys stay consistent with the
                // geohash side regardless of the hex casing. (upstream #645)
                val senderPubkey = rawSenderPubkey.lowercase()

                // Drop events from blocked geohash users (applies to account DMs too — parity with the
                // deleted handler's dataManager.isGeohashUserBlocked check).
                if (geohashRepository.isUserBlocked(senderPubkey)) return@launch

                val embedded = NostrEmbeddedBitChat.decode(content) ?: return@launch

                val convKey = "nostr_${senderPubkey.take(16)}"
                aliasRegistry.put(convKey, senderPubkey)
                if (geohash.isNotEmpty()) conversationRegistry.set(convKey, geohash)

                val timestamp = Instant.fromEpochMilliseconds(giftWrap.createdAt * 1000L)
                route(embedded, convKey, senderPubkey, timestamp, identity)
            } catch (e: Exception) {
                Log.e(TAG, "onGiftWrap error: ${e.message}")
            }
        }
    }

    private fun route(
        embedded: NostrEmbeddedBitChat.Embedded,
        convKey: String,
        senderPubkey: String,
        timestamp: Instant,
        identity: NostrIdentity,
    ) {
        when (embedded.type) {
            NoisePayloadType.PRIVATE_MESSAGE -> {
                val content = embedded.content ?: return
                val messageID = embedded.messageID ?: return

                // Favorite notifications ride the private-message channel; intercept before they show
                // up as chat text (parity with the mesh-side handleFavoriteNotificationFromMesh).
                if (content.startsWith("[FAVORITED]") || content.startsWith("[UNFAVORITED]")) {
                    handleFavoriteNotification(content, senderPubkey)
                    return
                }

                val message = BitchatMessage(
                    id = messageID,
                    sender = senderNickname(senderPubkey),
                    content = content,
                    timestamp = timestamp,
                    isRelay = false,
                    isPrivate = true,
                    senderPeerID = convKey,
                    deliveryStatus = DeliveryStatus.Delivered(to = senderPubkey.take(16), at = Clock.System.now()),
                )
                sink.addPrivateMessage(convKey, message)

                // Acknowledge delivery once (the no-recipient geohash ack path works for both account
                // and geohash DMs — it carries the sender pubkey + the identity we decrypted with).
                if (!seenMessageStore.hasDelivered(messageID)) {
                    nostrMessageSender.sendDeliveryAckGeohash(messageID, senderPubkey, identity)
                    seenMessageStore.markDelivered(messageID)
                }
            }
            NoisePayloadType.DELIVERED -> embedded.messageID?.let { sink.onDeliveryAck(it, convKey) }
            NoisePayloadType.READ_RECEIPT -> embedded.messageID?.let { sink.onReadReceipt(it, convKey) }
            NoisePayloadType.FILE_TRANSFER -> {
                val file = BitchatFilePacket.decode(embedded.payload.data) ?: run {
                    Log.w(TAG, "Failed to decode Nostr file transfer from $convKey"); return
                }
                val savedPath = incomingFileStore.saveIncomingFile(file)
                val message = BitchatMessage(
                    id = Uuid.random().toString().uppercase(),
                    sender = senderNickname(senderPubkey),
                    content = savedPath,
                    type = messageTypeForMime(file.mimeType),
                    timestamp = timestamp,
                    isRelay = false,
                    isPrivate = true,
                    senderPeerID = convKey,
                )
                sink.addPrivateMessage(convKey, message)
            }
            NoisePayloadType.VERIFY_CHALLENGE,
            NoisePayloadType.VERIFY_RESPONSE -> Unit // verification is not carried over Nostr DMs
        }
    }

    /**
     * Apply an incoming `[FAVORITED]`/`[UNFAVORITED]` notification to the favorites store, keyed by
     * the sender's Nostr pubkey (resolved to their Noise key). Drives mutual-favorite offline
     * reachability — the Nostr counterpart of MessageHandler.handleFavoriteNotificationFromMesh.
     */
    private fun handleFavoriteNotification(content: String, senderPubkey: String) {
        try {
            val isFavorite = content.startsWith("[FAVORITED]")
            val npub = content.substringAfter(":", "").trim().takeIf { it.startsWith("npub1") }
            val noiseKey = favoritesService.findNoiseKey(senderPubkey)
                ?: npub?.let { favoritesService.findNoiseKey(it) }
                ?: return
            favoritesService.updatePeerFavoritedUs(noiseKey, isFavorite)
            if (npub != null) favoritesService.updateNostrPublicKey(noiseKey, npub)
        } catch (e: Exception) {
            Log.e(TAG, "handleFavoriteNotification error: ${e.message}")
        }
    }

    private fun senderNickname(senderPubkey: String): String =
        runCatching { favoritesService.findNoiseKey(senderPubkey)?.let { favoritesService.getFavoriteStatus(it)?.peerNickname } }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "anon"

    private fun dedupe(id: String): Boolean = seenLock.withLock {
        if (!seenSet.add(id)) return@withLock true
        seenOrder.addLast(id)
        if (seenOrder.size > 2000) seenSet.remove(seenOrder.removeFirst())
        false
    }
}
