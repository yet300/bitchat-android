package com.yet.bitmessage.feature.chats.main.di

import com.yet.bitmessage.feature.chats.main.ChatsComponent
import com.yet.bitmessage.feature.chats.main.DefaultChatsComponentFactory
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo

@ContributesTo(AppScope::class)
@BindingContainer
abstract class ChatsBindings {
    @Binds
    internal abstract val DefaultChatsComponentFactory.bindChatsComponentFactory: ChatsComponent.Factory
}
