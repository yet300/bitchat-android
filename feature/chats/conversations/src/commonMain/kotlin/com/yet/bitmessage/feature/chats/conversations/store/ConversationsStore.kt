package com.yet.bitmessage.feature.chats.conversations.store

import com.app.domain.model.Conversation
import com.app.domain.model.ConversationId
import com.app.domain.model.Peer
import com.app.domain.model.Reachability
import com.app.domain.model.TransportKind
import com.app.domain.model.TransportState
import com.app.domain.model.TransportStatus
import com.arkivanov.mvikotlin.core.store.Store

internal interface ConversationsStore :
    Store<ConversationsStore.Intent, ConversationsStore.State, ConversationsStore.Label> {

    data class State(
        val isLoading: Boolean = true,
        val conversations: List<Conversation> = emptyList(),
        val reachability: Map<ConversationId, Reachability> = emptyMap(),
        val nearby: List<Peer> = emptyList(),
        val pinned: Set<ConversationId> = emptySet(),
        val muted: Set<ConversationId> = emptySet(),
        // Row id → stable (Noise-keyed) id for pin/mute comparison. Missing entries fall back to
        // the row's own id, so behavior is identical when no stable key can be resolved.
        val canonicalKeys: Map<ConversationId, ConversationId> = emptyMap(),
        val transports: List<TransportStatus> = emptyList(),
        val bannerDismissed: Boolean = false,
    ) {
        /**
         * Transports the user can re-enable in one tap. PERMISSION_REQUIRED surfaces for every
         * kind; OFF surfaces only for BLUETOOTH — the primary mesh radio, where an off adapter
         * silently breaks discovery. An off Wi-Fi Aware / Internet / Tor is a deliberate choice,
         * never a nag.
         */
        val transportsNeedingAttention: List<TransportKind>
            get() = transports.filter { it.state.needsAttention(it.kind) }.map { it.kind }

        /**
         * Whether the surfaced attention is at least partly a missing permission (vs. only an
         * off Bluetooth radio). Drives the banner copy: permission framing vs "Bluetooth is off".
         */
        val attentionNeedsPermission: Boolean
            get() = transports.any { it.state == TransportState.PERMISSION_REQUIRED }

        private fun TransportState.needsAttention(kind: TransportKind): Boolean =
            this == TransportState.PERMISSION_REQUIRED ||
                (this == TransportState.OFF && kind == TransportKind.BLUETOOTH)
    }

    /** Selection is navigation, owned by the component. */
    sealed interface Intent {
        data class TogglePin(val id: ConversationId) : Intent
        data class ToggleMute(val id: ConversationId) : Intent
        data object DismissBanner : Intent
    }

    sealed interface Action {
        data object Subscribe : Action
    }

    sealed interface Msg {
        data class Loaded(val conversations: List<Conversation>) : Msg
        data class ReachabilityLoaded(val reachability: Map<ConversationId, Reachability>) : Msg
        data class NearbyLoaded(val nearby: List<Peer>) : Msg
        data class PinnedLoaded(val pinned: Set<ConversationId>) : Msg
        data class MutedLoaded(val muted: Set<ConversationId>) : Msg
        data class CanonicalKeysLoaded(val canonicalKeys: Map<ConversationId, ConversationId>) : Msg
        data class TransportsLoaded(val transports: List<TransportStatus>) : Msg
        data object BannerDismissed : Msg
    }

    sealed interface Label
}
