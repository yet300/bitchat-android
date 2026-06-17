package com.yet.bitmessage.ui.screen.root

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.yet.bitmessage.feature.root.RootComponent
import com.yet.bitmessage.ui.screen.chat.ChatsContent
import com.yet.bitmessage.ui.screen.onboarding.OnboardingContent

@Composable
fun RootContent(component: RootComponent, modifier: Modifier = Modifier) {
    Children(stack = component.stack, modifier = modifier) {
        when (val child = it.instance) {
            is RootComponent.Child.Onboarding -> OnboardingContent(child.component)
            is RootComponent.Child.Chats -> ChatsContent(child.component)
        }
    }
}
