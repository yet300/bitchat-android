package com.app.data.di

import com.app.common.encoding.hexEncodedString
import com.app.data.DbMessagePersistence
import com.app.data.MessagePersistence
import com.app.data.repository.ChannelRepositoryImpl
import com.app.data.repository.ContactRepositoryImpl
import com.app.data.repository.ConversationPrefsRepositoryImpl
import com.app.data.repository.ConversationRepositoryImpl
import com.app.data.repository.DebugRepositoryImpl
import com.app.data.repository.GeohashBookmarksRepositoryImpl
import com.app.data.repository.GeohashRepositoryImpl
import com.app.data.repository.IdentityRepositoryImpl
import com.app.data.repository.LocationNotesRepositoryImpl
import com.app.data.repository.MeshSettingsRepositoryImpl
import com.app.data.repository.MessageRepositoryImpl
import com.app.data.repository.NotificationMutePolicyImpl
import com.app.data.repository.NotificationSettingsRepositoryImpl
import com.app.data.repository.OnboardingRepositoryImpl
import com.app.data.repository.PeerRepositoryImpl
import com.app.data.repository.SearchRepositoryImpl
import com.app.data.repository.PowRepositoryImpl
import com.app.data.repository.SettingsRepositoryImpl
import com.app.data.repository.SettingsStoreImpl
import com.app.data.repository.ThemeRepositoryImpl
import com.app.data.repository.TorRepositoryImpl
import com.app.data.repository.VerificationRepositoryImpl
import com.app.data.channel.ChannelCipher
import com.app.data.channel.EncryptionServiceChannelCipher
import com.app.data.database.DatabaseKeyProviderImpl
import com.app.data.nostr.CurrentGeohashSource
import com.app.data.routing.MeshRouteStrategy
import com.app.data.routing.MessageRouter
import com.app.data.routing.NostrRouteStrategy
import com.app.data.routing.RoutingCore
import com.app.data.routing.RoutingMessageTransport
import com.app.data.routing.RouteSelector
import com.app.data.session.MeshNoiseSessionPort
import com.app.transport.mesh.MeshService
import com.app.transport.routing.PeerKeyResolver
import com.app.transport.routing.RouteStrategy
import com.app.transport.routing.SessionInitiator
import com.app.domain.repository.ChannelRepository
import com.app.domain.repository.ContactRepository
import com.app.domain.repository.ConversationPrefsRepository
import com.app.domain.repository.ConversationRepository
import com.app.domain.repository.DatabaseKeyProvider
import com.app.domain.repository.DebugRepository
import com.app.domain.repository.GeohashBookmarksRepository
import com.app.domain.repository.GeohashRepository
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.LocationNotesRepository
import com.app.domain.repository.MessageRepository
import com.app.domain.repository.MeshSettingsRepository
import com.app.domain.repository.MessageTransport
import com.app.domain.repository.NoiseSessionPort
import com.app.domain.repository.NotificationMutePolicy
import com.app.domain.repository.NotificationSettingsRepository
import com.app.domain.repository.OnboardingRepository
import com.app.domain.repository.PeerRepository
import com.app.domain.repository.PowRepository
import com.app.domain.repository.SearchRepository
import com.app.domain.repository.SettingsRepository
import com.app.domain.repository.ThemeRepository
import com.app.domain.repository.TorRepository
import com.app.domain.repository.VerificationRepository
import com.app.common.settings.SettingsStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope

/**
 * Binds the data-layer repository implementations to their domain ports, and provides the routing
 * facade + narrow mesh ports. All platform-free now: the mesh-coupled impls depend on the commonMain
 * [MeshService] port (implemented by the androidMain `BluetoothMeshService`), so nothing here needs
 * an android type. The only androidMain data binding is [DataAndroidBindings], which adapts the
 * concrete mesh service and the incoming-file store.
 *
 * @Provides live in the [companion] object (a @BindingContainer needs a concrete receiver for them);
 * the @Binds are abstract members. Contributed to [AppScope]; the application graph aggregates this.
 */
@ContributesTo(AppScope::class)
@BindingContainer
abstract class DataBindings {
    @Binds
    internal abstract val SettingsStoreImpl.bindSettingsStore: SettingsStore

    @Binds
    internal abstract val SettingsRepositoryImpl.bindSettings: SettingsRepository

    @Binds
    internal abstract val ThemeRepositoryImpl.bindTheme: ThemeRepository

    @Binds
    internal abstract val MeshSettingsRepositoryImpl.bindMeshSettings: MeshSettingsRepository

    @Binds
    internal abstract val NotificationSettingsRepositoryImpl.bindNotificationSettings: NotificationSettingsRepository

    @Binds
    internal abstract val OnboardingRepositoryImpl.bindOnboarding: OnboardingRepository

    @Binds
    internal abstract val NotificationMutePolicyImpl.bindNotificationMutePolicy: NotificationMutePolicy

    @Binds
    internal abstract val ContactRepositoryImpl.bindContact: ContactRepository

    @Binds
    internal abstract val ChannelRepositoryImpl.bindChannel: ChannelRepository

    @Binds
    internal abstract val EncryptionServiceChannelCipher.bindChannelCipher: ChannelCipher

    @Binds
    internal abstract val DbMessagePersistence.bindMessagePersistence: MessagePersistence

    @Binds
    internal abstract val MessageRepositoryImpl.bindMessages: MessageRepository

    @Binds
    internal abstract val ConversationRepositoryImpl.bindConversations: ConversationRepository

    @Binds
    internal abstract val GeohashRepositoryImpl.bindGeohash: GeohashRepository

    // Same singleton also answers "what geohash am I in?" for outgoing geohash DMs (NostrMessageSender).
    @Binds
    internal abstract val GeohashRepositoryImpl.bindCurrentGeohashSource: CurrentGeohashSource

    @Binds
    internal abstract val GeohashBookmarksRepositoryImpl.bindGeohashBookmarks: GeohashBookmarksRepository

    @Binds
    internal abstract val LocationNotesRepositoryImpl.bindLocationNotes: LocationNotesRepository

    @Binds
    internal abstract val DebugRepositoryImpl.bindDebug: DebugRepository

    @Binds
    internal abstract val ConversationPrefsRepositoryImpl.bindConversationPrefs: ConversationPrefsRepository

    @Binds
    internal abstract val PeerRepositoryImpl.bindPeers: PeerRepository

    @Binds
    internal abstract val SearchRepositoryImpl.bindSearch: SearchRepository

    @Binds
    internal abstract val IdentityRepositoryImpl.bindIdentity: IdentityRepository

    // Thin adapters over the graph-owned transport preference managers (Tor, PoW) and the verification
    // service — moved from :shared androidMain (pure Kotlin, no android types).
    @Binds
    internal abstract val TorRepositoryImpl.bindTorRepository: TorRepository

    @Binds
    internal abstract val PowRepositoryImpl.bindPowRepository: PowRepository

    @Binds
    internal abstract val VerificationRepositoryImpl.bindVerificationRepository: VerificationRepository

    /**
     * Hardware-rooted SQLCipher passphrase provider — platform-free (CryptographyRandom + ioDispatcher);
     * only its secret-store backing is provided per-platform.
     */
    @Binds
    internal abstract val DatabaseKeyProviderImpl.bindDatabaseKeyProvider: DatabaseKeyProvider

    @Binds
    internal abstract val RoutingMessageTransport.bindTransport: MessageTransport

    @Binds
    internal abstract val MeshNoiseSessionPort.bindNoiseSession: NoiseSessionPort

    @Binds
    internal abstract val RouteSelector.bindRoutingCore: RoutingCore

    // §6 Tier-2 strategies, multibound into Set<RouteStrategy> (impls stay internal — DIP).
    @Binds
    @IntoSet
    internal abstract val MeshRouteStrategy.bindMeshRouteStrategy: RouteStrategy

    @Binds
    @IntoSet
    internal abstract val NostrRouteStrategy.bindNostrRouteStrategy: RouteStrategy

    companion object {

        /**
         * Wraps the graph-owned [RouteSelector] in the legacy [MessageRouter] facade, which keeps the
         * existing call-site API unchanged until Phase C dissolves the god-classes.
         */
        @Provides
        @SingleIn(AppScope::class)
        internal fun provideMessageRouter(
            routingCore: RoutingCore,
            scope: CoroutineScope,
        ): MessageRouter = MessageRouter(routingCore, scope)

        /** Narrow mesh ports (ISP) for the routing policy — implemented over the [MeshService] port. */
        @Provides
        fun provideSessionInitiator(mesh: MeshService): SessionInitiator =
            SessionInitiator { peerID -> mesh.initiateNoiseHandshake(peerID) }

        @Provides
        fun providePeerKeyResolver(mesh: MeshService): PeerKeyResolver =
            PeerKeyResolver { peerID ->
                try {
                    mesh.getPeerInfo(peerID)?.noisePublicKey?.hexEncodedString()
                } catch (_: Exception) {
                    null
                }
            }
    }
}
