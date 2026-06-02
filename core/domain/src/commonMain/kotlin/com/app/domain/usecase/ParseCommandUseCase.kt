package com.app.domain.usecase

import com.app.domain.model.Channel

/** Action "role" command (IRC style). */
enum class ActionKind(val verb: String, val obj: String) {
    HUG("gives", "a warm hug 🫂"),
    SLAP("slaps", "around a bit with a large trout 🐟"),
}

/** A parsed IRC-style command. */
sealed interface ChatCommand {
    data class Join(val channel: String, val password: String?) : ChatCommand
    data class Msg(val nickname: String, val body: String?) : ChatCommand
    data object Who : ChatCommand
    data object Clear : ChatCommand
    data object Channels : ChatCommand
    /** [nickname] == null -> list blocked users. */
    data class Block(val nickname: String?) : ChatCommand
    data class Unblock(val nickname: String) : ChatCommand
    data class Pass(val password: String?) : ChatCommand
    data class Action(val kind: ActionKind, val target: String) : ChatCommand
    data class Unknown(val name: String) : ChatCommand
    /** Command recognized but arguments are invalid — hint text for the user. */
    data class Usage(val text: String) : ChatCommand
}

/**
 * Pure command parser. Returns null if the string is NOT a command (does not start with '/').
 * Ported from CommandProcessor parsing; execution is delegated to separate use-cases/Store.
 */
class ParseCommandUseCase {

    operator fun invoke(input: String): ChatCommand? {
        if (!input.startsWith("/")) return null
        val parts = input.trim().split(" ").filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ChatCommand.Unknown("/")
        val cmd = parts.first().lowercase()
        val args = parts.drop(1)

        return when (cmd) {
            "/j", "/join" ->
                if (args.isEmpty()) ChatCommand.Usage("usage: /join <channel>")
                else ChatCommand.Join(Channel.tag(args[0]), args.getOrNull(1))

            "/m", "/msg" ->
                if (args.isEmpty()) ChatCommand.Usage("usage: /msg <nickname> [message]")
                else ChatCommand.Msg(args[0].removePrefix("@"), args.drop(1).joinToString(" ").ifEmpty { null })

            "/w" -> ChatCommand.Who
            "/clear" -> ChatCommand.Clear
            "/channels" -> ChatCommand.Channels

            "/block" -> ChatCommand.Block(args.firstOrNull()?.removePrefix("@"))

            "/unblock" ->
                if (args.isEmpty()) ChatCommand.Usage("usage: /unblock <nickname>")
                else ChatCommand.Unblock(args[0].removePrefix("@"))

            "/pass" -> ChatCommand.Pass(args.firstOrNull())

            "/hug" ->
                if (args.isEmpty()) ChatCommand.Usage("usage: /hug <nickname>")
                else ChatCommand.Action(ActionKind.HUG, args[0].removePrefix("@"))

            "/slap" ->
                if (args.isEmpty()) ChatCommand.Usage("usage: /slap <nickname>")
                else ChatCommand.Action(ActionKind.SLAP, args[0].removePrefix("@"))

            else -> ChatCommand.Unknown(cmd)
        }
    }
}
