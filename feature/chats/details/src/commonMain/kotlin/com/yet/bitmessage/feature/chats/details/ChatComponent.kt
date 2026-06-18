package com.yet.bitmessage.feature.chats.details

import com.app.domain.model.BitMessage
import com.app.domain.model.ConversationId
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

    /** The QR-verification scanner sheet (per-contact verify), or none. */
    val verifyScan: Value<ChildSlot<*, VerifyScanComponent>>

    fun onDraftChanged(text: String)

    fun onSendClicked()

    /** Open the QR-scan verification sheet for this DM. */
    fun onVerifyClicked()

    fun onDismissVerifyScan()

    /** Close the details panel (panels SINGLE mode back navigation). */
    fun onBackClicked()

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
        val targetMessageId: String?,
    )

    fun interface Factory {
        fun create(
            componentContext: ComponentContext,
            config: ChatConfig,
            onFinished: () -> Unit,
        ): ChatComponent
    }
}
