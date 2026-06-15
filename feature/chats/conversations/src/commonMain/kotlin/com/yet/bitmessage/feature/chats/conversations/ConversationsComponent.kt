package com.yet.bitmessage.feature.chats.conversations

import com.app.domain.model.ConversationId
import com.app.domain.model.Peer
import com.app.domain.model.TransportKind
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value

/**
 * Chat list (main panel). Emits the selected [ConversationId] upward — the root
 * component owns navigation.
 */
interface ConversationsComponent {

    val model: Value<Model>

    fun onConversationClicked(id: ConversationId)

    /** Open (or start) a DM with an in-range peer from the nearby rail. */
    fun onNearbyClicked(peer: Peer)

    /** Request the connectivity panel (hosted as a ChildSlot by the parent). */
    fun onConnectivityClicked()

    fun onTogglePin(id: ConversationId)

    fun onToggleMute(id: ConversationId)

    fun onQueryChanged(text: String)

    /** Dismiss the connectivity attention banner for this session. */
    fun onDismissBanner()

    data class Model(
        val isLoading: Boolean,
        val conversations: List<ConversationRow>,
        val nearby: List<Peer>,
        val query: String,
        val connectivityBanner: ConnectivityBanner? = null,
    )

    /** A dismissible prompt atop the list when one or more transports need re-enabling. */
    data class ConnectivityBanner(val transports: List<TransportKind>)

    fun interface Factory {
        fun create(
            componentContext: ComponentContext,
            onConversationSelected: (ConversationId) -> Unit,
            onConnectivityRequested: () -> Unit,
        ): ConversationsComponent
    }
}
