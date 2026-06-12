package com.yet.bitmessage.feature.chats.conversations.di

import com.yet.bitmessage.feature.chats.conversations.ConversationsComponent
import com.yet.bitmessage.feature.chats.conversations.DefaultConversationsComponentFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo

@ContributesTo(AppScope::class)
@BindingContainer
abstract class ConversationsBindings {
    @Binds
    internal abstract val DefaultConversationsComponentFactory.bindConversationsComponentFactory: ConversationsComponent.Factory
}
