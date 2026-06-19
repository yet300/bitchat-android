package com.app.data.di

import com.app.data.repository.ChannelRepositoryImpl
import com.app.data.repository.ContactRepositoryImpl
import com.app.data.repository.ConversationPrefsRepositoryImpl
import com.app.data.repository.ConversationRepositoryImpl
import com.app.data.repository.GeohashBookmarksRepositoryImpl
import com.app.data.repository.GeohashRepositoryImpl
import com.app.data.repository.IdentityRepositoryImpl
import com.app.data.repository.MeshSettingsRepositoryImpl
import com.app.data.repository.MessageRepositoryImpl
import com.app.data.repository.NotificationMutePolicyImpl
import com.app.data.repository.NotificationSettingsRepositoryImpl
import com.app.data.repository.OnboardingRepositoryImpl
import com.app.data.repository.PeerRepositoryImpl
import com.app.data.repository.SearchRepositoryImpl
import com.app.data.repository.SettingsRepositoryImpl
import com.app.data.repository.SettingsStoreImpl
import com.app.data.repository.ThemeRepositoryImpl
import com.app.data.routing.MeshRouteStrategy
import com.app.data.routing.NostrRouteStrategy
import com.app.data.routing.RoutingCore
import com.app.data.routing.RoutingMessageTransport
import com.app.data.routing.RouteSelector
import com.app.transport.routing.RouteStrategy
import com.app.domain.repository.ChannelRepository
import com.app.domain.repository.ContactRepository
import com.app.domain.repository.ConversationPrefsRepository
import com.app.domain.repository.ConversationRepository
import com.app.domain.repository.GeohashBookmarksRepository
import com.app.domain.repository.GeohashRepository
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.MessageRepository
import com.app.domain.repository.MeshSettingsRepository
import com.app.domain.repository.MessageTransport
import com.app.domain.repository.NotificationMutePolicy
import com.app.domain.repository.NotificationSettingsRepository
import com.app.domain.repository.OnboardingRepository
import com.app.domain.repository.PeerRepository
import com.app.domain.repository.SearchRepository
import com.app.domain.repository.SettingsRepository
import com.app.domain.repository.ThemeRepository
import com.app.common.settings.SettingsStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo

/**
 * Binds the data-layer repository implementations to their domain ports. Contributed to [AppScope];
 * the application graph (Phase B4) aggregates this together with the platform providers that supply
 * `ObservableSettings`, `FavoritesPersistenceService` and `PeerFingerprintManager`.
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
    internal abstract val MessageRepositoryImpl.bindMessages: MessageRepository

    @Binds
    internal abstract val ConversationRepositoryImpl.bindConversations: ConversationRepository

    @Binds
    internal abstract val GeohashRepositoryImpl.bindGeohash: GeohashRepository

    @Binds
    internal abstract val GeohashBookmarksRepositoryImpl.bindGeohashBookmarks: GeohashBookmarksRepository

    @Binds
    internal abstract val ConversationPrefsRepositoryImpl.bindConversationPrefs: ConversationPrefsRepository

    @Binds
    internal abstract val PeerRepositoryImpl.bindPeers: PeerRepository

    @Binds
    internal abstract val SearchRepositoryImpl.bindSearch: SearchRepository

    @Binds
    internal abstract val IdentityRepositoryImpl.bindIdentity: IdentityRepository

    @Binds
    internal abstract val RoutingMessageTransport.bindTransport: MessageTransport

    @Binds
    internal abstract val RouteSelector.bindRoutingCore: RoutingCore

    // §6 Tier-2 strategies, multibound into Set<RouteStrategy> (impls stay internal — DIP)
    @Binds
    @IntoSet
    internal abstract val MeshRouteStrategy.bindMeshRouteStrategy: RouteStrategy

    @Binds
    @IntoSet
    internal abstract val NostrRouteStrategy.bindNostrRouteStrategy: RouteStrategy
}
