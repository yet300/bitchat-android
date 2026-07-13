package com.yet.bitmessage.feature.chats.details.store

import com.app.domain.model.Attachment
import com.app.domain.model.BitMessage
import com.app.domain.model.ConversationId
import com.app.domain.model.GeoPerson
import com.app.domain.model.Reachability
import com.arkivanov.mvikotlin.core.store.Store

internal interface ChatStore : Store<ChatStore.Intent, ChatStore.State, ChatStore.Label> {

    data class State(
        val conversationId: ConversationId,
        val title: String,
        val isLoading: Boolean = true,
        val messages: List<BitMessage> = emptyList(),
        val draft: String = "",
        val reachability: Reachability = Reachability.OFFLINE,
        val isVerified: Boolean = false,
        /** Derived "web of trust" tier: a peer I have not verified but a verified peer vouches for. */
        val isVouched: Boolean = false,
        /** How many of my verified peers currently vouch for this peer. */
        val voucherCount: Int = 0,
        val participantCount: Int = 0,
        /** Whether the current geohash is bookmarked (false for non-geo chats). */
        val isBookmarked: Boolean = false,
        /** Live participants for a geo chat (empty for other kinds). */
        val participants: List<GeoPerson> = emptyList(),
        /** Nicknames the composer can @-mention (live peers / geo participants). */
        val mentionCandidates: Set<String> = emptySet(),
        /** Message to scroll to / highlight on open (from a Messages search result); static. */
        val targetMessageId: String? = null,
    ) {
        val canSend: Boolean get() = draft.isNotBlank()

        /** Private DMs are Noise end-to-end encrypted; public/channel/geo are broadcast. */
        val isEncrypted: Boolean get() = conversationId is ConversationId.Private

        /** Candidates for the @-token currently being typed at the end of the draft. */
        val mentionSuggestions: List<String> get() = mentionSuggestionsFor(draft, mentionCandidates)
    }

    sealed interface Intent {
        data class DraftChanged(val text: String) : Intent
        data object SendClicked : Intent

        data class ParticipantClicked(val pubkeyHex: String) : Intent

        data class SendAttachment(val attachment: Attachment) : Intent

        data class CancelTransfer(val messageId: String) : Intent

        /** Complete the in-progress @-token with [nickname]. */
        data class MentionSelected(val nickname: String) : Intent

        /** Bookmark / unbookmark the current geohash channel. */
        data object ToggleBookmark : Intent
    }

    sealed interface Action {
        data object Subscribe : Action
    }

    sealed interface Msg {
        data class Loaded(val messages: List<BitMessage>) : Msg
        data class DraftChanged(val text: String) : Msg
        data class TitleResolved(val title: String) : Msg
        data class ReachabilityChanged(val reachability: Reachability) : Msg
        data class VerifiedChanged(val verified: Boolean) : Msg
        data class VouchChanged(val isVouched: Boolean, val voucherCount: Int) : Msg
        data class ParticipantCountChanged(val count: Int) : Msg
        data class ParticipantsChanged(val participants: List<GeoPerson>) : Msg
        data class MentionCandidatesChanged(val nicknames: Set<String>) : Msg
        data class BookmarkChanged(val isBookmarked: Boolean) : Msg
    }

    sealed interface Label {
        data class OpenConversation(val id: ConversationId) : Label
    }
}

/** The @-token being typed at the very end of the draft (empty group right after a bare `@`). */
internal val TRAILING_MENTION_REGEX = Regex("@([a-zA-Z0-9_]*)$")

/** Candidates that match the trailing @-token (excluding an already-complete exact match). */
private fun mentionSuggestionsFor(draft: String, candidates: Set<String>): List<String> {
    val query = TRAILING_MENTION_REGEX.find(draft)?.groupValues?.get(1) ?: return emptyList()
    return candidates
        .filter { it.startsWith(query, ignoreCase = true) && !it.equals(query, ignoreCase = true) }
        .sorted()
        .take(MAX_MENTION_SUGGESTIONS)
}

private const val MAX_MENTION_SUGGESTIONS = 5
