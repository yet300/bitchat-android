package com.bitchat.android.core.domain.usecase

/**
 * Pure parsing of @-mentions. Returns only the nicknames that are actually known
 * ([knownNicknames] = peer nicknames + own). Ported from MessageManager.parseMentions.
 */
class ParseMentionsUseCase {

    operator fun invoke(content: String, knownNicknames: Set<String>): List<String> =
        MENTION_REGEX.findAll(content)
            .map { it.groupValues[1] }
            .filter { it in knownNicknames }
            .distinct()
            .toList()

    private companion object {
        val MENTION_REGEX = Regex("@([a-zA-Z0-9_]+)")
    }
}
