package com.yet.bitmessage.ui.screen.chat

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.experimental.panels.ChildPanels
import com.arkivanov.decompose.extensions.compose.experimental.panels.HorizontalChildPanelsLayout
import com.arkivanov.decompose.router.panels.ChildPanelsMode
import com.yet.bitmessage.feature.chats.main.ChatsComponent

@Composable
fun ChatsContent(component: ChatsComponent, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier) {
        val mode = if (maxWidth >= 800.dp) ChildPanelsMode.DUAL else ChildPanelsMode.SINGLE
        LaunchedEffect(mode) {
            component.setMode(mode)
        }
        ChildPanels(
            panels = component.panels,
            mainChild = { child ->
                when (val main = child.instance) {
                    is ChatsComponent.Main.Conversations -> ConversationsContent(main.component)
                }
            },
            detailsChild = { child ->
                when (val details = child.instance) {
                    is ChatsComponent.Details.Chat -> ChatContent(details.component)
                }
            },
            layout = HorizontalChildPanelsLayout(dualWeights = 0.4f to 0.6f),
        )
    }
}
