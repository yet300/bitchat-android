@file:OptIn(ExperimentalTime::class)

package com.yet.bitmessage.feature.chats.conversations

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Relative timestamp bucket for a conversation row. Pure (no platform formatting / locale) so the
 * UI layer decides the actual string — the same split iOS Messages uses (time today, "Yesterday",
 * a date earlier).
 */
sealed interface ConversationTimeLabel {
    data class Today(val hour: Int, val minute: Int) : ConversationTimeLabel
    data object Yesterday : ConversationTimeLabel
    data class Earlier(val day: Int, val month: Int, val year: Int, val currentYear: Boolean) : ConversationTimeLabel
    data object None : ConversationTimeLabel
}

fun conversationTimeLabel(
    lastActivity: Instant?,
    now: Instant,
    zone: TimeZone,
): ConversationTimeLabel {
    if (lastActivity == null) return ConversationTimeLabel.None
    val moment = lastActivity.toLocalDateTime(zone)
    val today = now.toLocalDateTime(zone).date
    val date = moment.date
    return when (date) {
        today -> ConversationTimeLabel.Today(moment.hour, moment.minute)
        today.minus(1, DateTimeUnit.DAY) -> ConversationTimeLabel.Yesterday
        else -> ConversationTimeLabel.Earlier(
            day = date.dayOfMonth,
            month = date.monthNumber,
            year = date.year,
            currentYear = date.year == today.year,
        )
    }
}
