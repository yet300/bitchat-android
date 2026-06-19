package com.yet.bitmessage.feature.chats.details.di

import com.yet.bitmessage.feature.chats.details.ChatComponent
import com.yet.bitmessage.feature.chats.details.DefaultChatComponentFactory
import com.yet.bitmessage.feature.chats.details.notes.DefaultLocationNotesComponentFactory
import com.yet.bitmessage.feature.chats.details.notes.LocationNotesComponent
import com.yet.bitmessage.feature.chats.details.verify.DefaultVerifyScanComponentFactory
import com.yet.bitmessage.feature.chats.details.verify.VerifyScanComponent
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo

@ContributesTo(AppScope::class)
@BindingContainer
abstract class ChatBindings {
    @Binds
    internal abstract val DefaultChatComponentFactory.bindChatComponentFactory: ChatComponent.Factory

    @Binds
    internal abstract val DefaultVerifyScanComponentFactory.bindVerifyScanComponentFactory: VerifyScanComponent.Factory

    @Binds
    internal abstract val DefaultLocationNotesComponentFactory.bindLocationNotesComponentFactory: LocationNotesComponent.Factory
}
