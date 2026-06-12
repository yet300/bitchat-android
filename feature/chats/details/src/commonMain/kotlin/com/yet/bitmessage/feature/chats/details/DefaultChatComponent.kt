package com.yet.bitmessage.feature.chats.details

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import dev.zacsweers.metro.Inject

internal class DefaultChatComponent(
    componentContext: ComponentContext,
    config: ChatConfig,
    private val onFinished: () -> Unit,
) : ChatComponent, ComponentContext by componentContext {

    override val model: Value<ChatComponent.Model> = MutableValue(
        ChatComponent.Model(
            conversationId = config.toConversationId(),
            title = config.titleFallback(),
        ),
    )

    override fun onBackClicked() = onFinished()
}

/** Display title until the chat store provides the real one (nickname resolution etc.). */
private fun ChatConfig.titleFallback(): String = when (this) {
    is ChatConfig.PublicMesh -> "mesh"
    is ChatConfig.Channel -> tag
    is ChatConfig.Private -> peerRaw.take(8)
    is ChatConfig.Geohash -> "#$geohash"
}

@Inject
internal class DefaultChatComponentFactory : ChatComponent.Factory {
    override fun create(
        componentContext: ComponentContext,
        config: ChatConfig,
        onFinished: () -> Unit,
    ): ChatComponent = DefaultChatComponent(componentContext, config, onFinished)
}
