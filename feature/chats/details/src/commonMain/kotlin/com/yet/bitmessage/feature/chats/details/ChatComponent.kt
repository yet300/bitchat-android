package com.yet.bitmessage.feature.chats.details

import com.app.domain.model.Attachment
import com.app.domain.model.BitMessage
import com.app.domain.model.ConversationId
import com.app.domain.model.GeoPerson
import com.app.domain.model.Reachability
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.value.Value
import com.yet.bitmessage.feature.chats.details.verify.VerifyScanComponent

/**
 * Single conversation screen (details panel): a live message timeline backed by
 * [MessageRepository][com.app.domain.repository.MessageRepository] plus a text input
 * that sends through the domain transport.
 */
interface ChatComponent {

    val model: Value<Model>

    val sheetSlot: Value<ChildSlot<*, ChatSheetChild>>

    fun onDraftChanged(text: String)

    fun onSendClicked()

    /** Send a picked media attachment (image / audio / file) into this conversation. */
    fun onAttachmentPicked(attachment: Attachment)

    /** Cancel an in-flight outgoing attachment transfer and drop its message. */
    fun onCancelTransfer(messageId: String)

    /** Complete the @-token being typed with [nickname]. */
    fun onMentionSelected(nickname: String)

    /** Open the QR-scan verification sheet for this DM. */
    fun onVerifyClicked()

    /** Open the geo-participants sheet ("who's here"). */
    fun onParticipantsClicked()

    fun onParticipantSelected(pubkeyHex: String)

    fun onDismissSheet()

    fun onBackClicked()

    sealed interface ChatSheetChild {
        class VerifyScan(val component: VerifyScanComponent) : ChatSheetChild
        data object Participants : ChatSheetChild
    }

    data class Model(
        val conversationId: ConversationId,
        val title: String,
        val isLoading: Boolean,
        val messages: List<BitMessage>,
        val draft: String,
        val canSend: Boolean,
        val reachability: Reachability,
        val isEncrypted: Boolean,
        val isVerified: Boolean,
        val participantCount: Int,
        val participants: List<GeoPerson>,
        val mentionSuggestions: List<String>,
        val targetMessageId: String?,
    )

    fun interface Factory {
        fun create(
            componentContext: ComponentContext,
            config: ChatConfig,
            onFinished: () -> Unit,
            onOpenConversation: (ConversationId) -> Unit,
        ): ChatComponent
    }
}
