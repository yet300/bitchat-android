package com.yet.bitmessage.feature.chats.details.di

import com.yet.bitmessage.feature.chats.details.ChatComponent
import com.yet.bitmessage.feature.chats.details.DefaultChatComponentFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo

@ContributesTo(AppScope::class)
@BindingContainer
abstract class ChatBindings {
    @Binds
    internal abstract val DefaultChatComponentFactory.bindChatComponentFactory: ChatComponent.Factory
}
