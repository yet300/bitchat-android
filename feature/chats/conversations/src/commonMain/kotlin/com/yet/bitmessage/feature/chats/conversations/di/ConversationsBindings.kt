package com.yet.bitmessage.feature.chats.conversations.di

import com.yet.bitmessage.feature.chats.conversations.ConversationsComponent
import com.yet.bitmessage.feature.chats.conversations.DefaultConversationsComponentFactory
import com.yet.bitmessage.feature.chats.conversations.connectivity.ConnectivityComponent
import com.yet.bitmessage.feature.chats.conversations.connectivity.DefaultConnectivityComponentFactory
import com.yet.bitmessage.feature.chats.conversations.search.DefaultSearchComponentFactory
import com.yet.bitmessage.feature.chats.conversations.search.SearchComponent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo

@ContributesTo(AppScope::class)
@BindingContainer
abstract class ConversationsBindings {
    @Binds
    internal abstract val DefaultConversationsComponentFactory.bindConversationsComponentFactory: ConversationsComponent.Factory

    @Binds
    internal abstract val DefaultConnectivityComponentFactory.bindConnectivityComponentFactory: ConnectivityComponent.Factory

    @Binds
    internal abstract val DefaultSearchComponentFactory.bindSearchComponentFactory: SearchComponent.Factory
}
