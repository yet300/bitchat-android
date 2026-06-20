package com.app.domain.usecase

import com.app.domain.model.ConversationId
import com.app.domain.model.RetentionPolicy
import com.app.domain.repository.ChannelRepository
import com.app.domain.repository.ContactRepository
import com.app.domain.repository.JoinResult
import com.app.domain.repository.MessageRepository
import com.app.domain.repository.PeerRepository
import kotlinx.coroutines.flow.first

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
    private val channels: ChannelRepository,
    private val contacts: ContactRepository,
    private val peers: PeerRepository,
) {
    suspend operator fun invoke(conversationId: ConversationId, command: ChatCommand): CommandResult =
        when (command) {
            is ChatCommand.Join -> when (val r = channels.join(command.channel, command.password)) {
                JoinResult.Joined -> CommandResult.Feedback("Joined ${command.channel}")
                JoinResult.NeedsPassword ->
                    CommandResult.Feedback("${command.channel} is password-protected: /join ${command.channel} <password>")
                is JoinResult.Failed -> CommandResult.Feedback(r.reason)
            }

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

            ChatCommand.Channels -> {
                val joined = channels.observeJoinedChannels().first().sorted()
                CommandResult.Feedback(if (joined.isEmpty()) "No channels joined" else "Channels: ${joined.joinToString(", ")}")
            }

            is ChatCommand.Block -> {
                val nickname = command.nickname
                    ?: return CommandResult.Feedback("usage: /block <nickname>")
                blockByNickname(nickname, blocked = true)
            }

            is ChatCommand.Unblock -> blockByNickname(command.nickname, blocked = false)

            is ChatCommand.Pass -> {
                val tag = (conversationId as? ConversationId.Channel)?.tag
                    ?: return CommandResult.Feedback("usage: /pass <password> — only inside a channel")
                val password = command.password
                    ?: return CommandResult.Feedback("usage: /pass <password>")
                channels.setPassword(tag, password)
                CommandResult.Feedback("Password set for $tag")
            }

            ChatCommand.Save -> {
                val tag = (conversationId as? ConversationId.Channel)?.tag
                    ?: return CommandResult.Feedback("usage: /save — only inside a channel")
                // Local retention only: legacy /save broadcast an owner toggle to channel members,
                // a protocol this rewrite does not carry. Set the device-local retention to keep
                // everything; finer policies (day/week/month) live in the channel-management surface.
                channels.setRetention(tag, RetentionPolicy.KEEP_ALL)
                CommandResult.Feedback("Messages in $tag will be kept on this device")
            }

            is ChatCommand.Transfer -> {
                command.nickname
                    ?: return CommandResult.Feedback("usage: /transfer <nickname>")
                // Parity: channel-ownership transfer was advertised by the legacy command palette but
                // never executed (no creator/ownership state is resolvable yet). Acknowledge instead
                // of routing to a non-existent port.
                CommandResult.Feedback("Channel ownership transfer is not available")
            }

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
