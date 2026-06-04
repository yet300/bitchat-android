package com.app.data.di

import com.app.data.repository.ChannelRepositoryImpl
import com.app.data.repository.ContactRepositoryImpl
import com.app.data.repository.ConversationRepositoryImpl
import com.app.data.repository.MessageRepositoryImpl
import com.app.data.repository.PeerRepositoryImpl
import com.app.data.repository.SettingsRepositoryImpl
import com.app.domain.repository.ChannelRepository
import com.app.domain.repository.ContactRepository
import com.app.domain.repository.ConversationRepository
import com.app.domain.repository.MessageRepository
import com.app.domain.repository.PeerRepository
import com.app.domain.repository.SettingsRepository
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
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
    internal abstract val SettingsRepositoryImpl.bindSettings: SettingsRepository

    @Binds
    internal abstract val ContactRepositoryImpl.bindContact: ContactRepository

    @Binds
    internal abstract val ChannelRepositoryImpl.bindChannel: ChannelRepository

    @Binds
    internal abstract val MessageRepositoryImpl.bindMessages: MessageRepository

    @Binds
    internal abstract val ConversationRepositoryImpl.bindConversations: ConversationRepository

    @Binds
    internal abstract val PeerRepositoryImpl.bindPeers: PeerRepository
}
