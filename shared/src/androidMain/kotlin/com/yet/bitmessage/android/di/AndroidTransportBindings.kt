package com.yet.bitmessage.android.di

import com.app.crypto.EncryptionService
import com.app.data.AppStateStore
import com.app.data.favorites.FavoritesPersistenceService
import com.app.data.routing.MessageRouter
import com.app.transport.FavoriteNostrLink
import com.app.transport.GeohashReadReceiptRouter
import com.app.transport.IncomingMessageSink
import com.app.transport.model.ReadReceipt
import com.app.transport.net.ArtiTorManager
import com.app.transport.net.SocksAddressSource
import com.app.transport.nostr.GeohashAliasRegistry
import com.app.transport.nostr.NostrIdentityBridge
import com.app.transport.routing.MeshPeerIdSource
import com.app.transport.routing.NostrIdentityProvider
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * App-agnostic transport-wiring SPIs consumed by [com.app.transport.mesh.BluetoothMeshService].
 * These delegate only to :core modules, so they live in :shared with the rest of the DI graph.
 * The providers that touch :app resources / classes (ServiceNotifier → NotificationManager + R,
 * NicknameSource → DataManager) stay in the app-resident `AndroidAppBindings`.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object AndroidTransportBindings {

    /** The process-wide in-memory store the UI hydrates from doubles as the incoming sink. */
    @Provides
    fun provideIncomingMessageSink(store: AppStateStore): IncomingMessageSink = store

    /** Noise<->Nostr favorite mapping, backed by the graph-owned favorites service. */
    @Provides
    @SingleIn(AppScope::class)
    fun provideFavoriteNostrLink(favoritesService: FavoritesPersistenceService): FavoriteNostrLink =
        object : FavoriteNostrLink {
            override fun updatePeerFavoritedUs(noiseKey: ByteArray, theyFavoritedUs: Boolean) {
                favoritesService.updatePeerFavoritedUs(noiseKey, theyFavoritedUs)
            }
            override fun updateNostrPublicKey(noiseKey: ByteArray, nostrPubkey: String) {
                favoritesService.updateNostrPublicKey(noiseKey, nostrPubkey)
            }
            override fun updateNostrPublicKeyForPeerId(peerId: String, nostrPubkey: String) {
                favoritesService.updateNostrPublicKeyForPeerID(peerId, nostrPubkey)
            }
            override fun findNostrPubkey(noiseKey: ByteArray): String? =
                favoritesService.findNostrPubkey(noiseKey)
            override fun isFavorite(noiseKey: ByteArray): Boolean =
                favoritesService.getFavoriteStatus(noiseKey)?.isFavorite == true
        }

    /**
     * Current Nostr identity for the routing strategies — wraps the static
     * NostrIdentityBridge so :core:data stays Context-free.
     */
    @Provides
    fun provideNostrIdentityProvider(bridge: NostrIdentityBridge): NostrIdentityProvider =
        NostrIdentityProvider {
            try {
                bridge.getCurrentNostrIdentity()
            } catch (_: Exception) {
                null
            }
        }

    /**
     * SOCKS address for Tor-routed traffic. Lazy<ArtiTorManager> breaks the construction
     * cycle (the manager resets HttpClientProvider's cached clients on Tor state changes);
     * the address is consulted per connection, by which time the manager exists.
     */
    @Provides
    fun provideSocksAddressSource(arti: Lazy<ArtiTorManager>): SocksAddressSource =
        SocksAddressSource { arti.value.currentSocksAddress() }

    /**
     * Our own mesh peer id, read live from the Noise identity fingerprint — the same
     * derivation BMS uses, so it stays correct across panic resets with no re-wiring.
     */
    @Provides
    fun provideMeshPeerIdSource(encryptionService: EncryptionService): MeshPeerIdSource =
        MeshPeerIdSource { encryptionService.getIdentityFingerprint().take(16) }

    /**
     * Routes read receipts over the relay when the recipient is a geohash alias.
     * Lazy<MessageRouter> breaks the BMS <-> MessageRouter construction cycle: building
     * BMS only captures the handle; the router is resolved on first routed receipt, by
     * which time the graph has finished constructing it.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideGeohashReadReceiptRouter(
        messageRouter: Lazy<MessageRouter>,
        aliasRegistry: GeohashAliasRegistry,
    ): GeohashReadReceiptRouter = GeohashReadReceiptRouter { messageId, toPeerId ->
        val isGeoAlias = runCatching { aliasRegistry.snapshot().containsKey(toPeerId) }.getOrDefault(false)
        if (isGeoAlias) {
            messageRouter.value.sendReadReceipt(ReadReceipt(messageId), toPeerId)
            true
        } else {
            false
        }
    }
}
