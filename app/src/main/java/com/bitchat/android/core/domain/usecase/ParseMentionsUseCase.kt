package com.bitchat.android.core.domain.usecase

/**
 * Чистый парсинг @-упоминаний. Возвращает только те ники, что реально известны
 * ([knownNicknames] = ники пиров + собственный). Перенос логики MessageManager.parseMentions.
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
