package com.bitchat.android.di

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.app.data.AppStateStore
import com.app.data.favorites.FavoritesPersistenceService
import com.app.data.routing.MessageRouter
import com.app.transport.FavoriteNostrLink
import com.app.transport.GeohashReadReceiptRouter
import com.app.transport.IncomingMessageSink
import com.app.transport.NicknameSource
import com.app.transport.model.ReadReceipt
import com.app.transport.notification.ServiceNotifier
import com.app.transport.nostr.GeohashAliasRegistry
import com.app.transport.nostr.NostrIdentityBridge
import com.app.transport.routing.NostrIdentityProvider
import com.bitchat.android.ui.DataManager
import com.bitchat.android.ui.NotificationManager
import com.bitchat.android.util.NotificationIntervalManager
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import com.russhwolf.settings.ObservableSettings
import dev.zacsweers.metro.SingleIn

/**
 * App-side implementations of the transport wiring SPIs consumed by
 * [com.app.transport.mesh.BluetoothMeshService]. Formerly assembled imperatively by
 * MeshServiceHolder.configure(); now ordinary graph bindings so BMS receives them as
 * constructor parameters.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object AndroidAppBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun provideServiceNotifier(context: Context): ServiceNotifier = NotificationManager(
        context,
        NotificationManagerCompat.from(context),
        NotificationIntervalManager(),
    )

    @Provides
    @SingleIn(AppScope::class)
    fun provideNicknameSource(settings: ObservableSettings): NicknameSource {
        val dataManager = DataManager(settings)
        return NicknameSource { fallback ->
            try {
                dataManager.loadNickname().ifBlank { fallback }
            } catch (_: Exception) {
                fallback
            }
        }
    }

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
    fun provideNostrIdentityProvider(context: Context): NostrIdentityProvider =
        NostrIdentityProvider {
            try {
                NostrIdentityBridge.getCurrentNostrIdentity(context)
            } catch (_: Exception) {
                null
            }
        }

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
