package com.app.domain.usecase

import com.app.domain.model.ConversationId
import com.app.domain.repository.ContactRepository
import com.app.domain.repository.MessageRepository
import com.app.domain.repository.PeerRepository

/** Outcome of executing a [ChatCommand]; the caller renders feedback or routes a send. */
sealed interface CommandResult {
    /** Show this text as a local system line in the current conversation. */
    data class Feedback(val text: String) : CommandResult

    /** Send this text as an ordinary message in the current conversation (emotes: /hug, /slap). */
    data class SendAsMessage(val text: String) : CommandResult

    /** Side effect done; nothing to render. */
    data object Handled : CommandResult
}

/**
 * Executes a parsed [ChatCommand] against the existing ports (parity with the legacy
 * CommandProcessor). Pure orchestration: it performs the side effect and returns what the caller
 * should do, but does not itself construct timeline messages or navigate. Commands whose natural
 * effect is navigation (`/msg`) currently return guidance feedback — opening the conversation is a
 * follow-up slice.
 */
class ProcessCommandUseCase(
    private val messages: MessageRepository,
    private val contacts: ContactRepository,
    private val peers: PeerRepository,
) {
    suspend operator fun invoke(conversationId: ConversationId, command: ChatCommand): CommandResult =
        when (command) {
            is ChatCommand.Msg ->
                CommandResult.Feedback("Open a DM with ${command.nickname} from Search or Contacts")

            ChatCommand.Who -> {
                val online = peers.snapshot().filter { it.isConnected }.map { it.nickname }.sorted()
                CommandResult.Feedback(if (online.isEmpty()) "No one nearby" else "Online: ${online.joinToString(", ")}")
            }

            ChatCommand.Clear -> {
                messages.clear(conversationId)
                CommandResult.Handled
            }

            is ChatCommand.Block -> {
                val nickname = command.nickname
                    ?: return CommandResult.Feedback("usage: /block <nickname>")
                blockByNickname(nickname, blocked = true)
            }

            is ChatCommand.Unblock -> blockByNickname(command.nickname, blocked = false)

            is ChatCommand.Action ->
                CommandResult.SendAsMessage("${command.kind.verb} ${command.target} ${command.kind.obj}")

            is ChatCommand.Usage -> CommandResult.Feedback(command.text)
            is ChatCommand.Unknown -> CommandResult.Feedback("Unknown command: ${command.name}")
        }

    private suspend fun blockByNickname(nickname: String, blocked: Boolean): CommandResult {
        val peer = peers.snapshot().firstOrNull { it.nickname.equals(nickname, ignoreCase = true) }
            ?: return CommandResult.Feedback("No peer named $nickname")
        contacts.setBlocked(peer.id, blocked)
        return CommandResult.Feedback(if (blocked) "Blocked $nickname" else "Unblocked $nickname")
    }
}
