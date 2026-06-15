@file:OptIn(ExperimentalTime::class)

package com.yet.bitmessage.feature.chats.conversations

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class ConversationTimeLabelTest {

    private val utc = TimeZone.UTC
    // 2026-06-15T10:30:00Z
    private val now = Instant.parse("2026-06-15T10:30:00Z")

    @Test
    fun null_activity_is_none() {
        assertEquals(ConversationTimeLabel.None, conversationTimeLabel(null, now, utc))
    }

    @Test
    fun same_day_is_today_with_hh_mm() {
        val label = conversationTimeLabel(Instant.parse("2026-06-15T08:05:00Z"), now, utc)
        assertEquals(ConversationTimeLabel.Today(hour = 8, minute = 5), label)
    }

    @Test
    fun previous_day_is_yesterday() {
        val label = conversationTimeLabel(Instant.parse("2026-06-14T23:59:00Z"), now, utc)
        assertEquals(ConversationTimeLabel.Yesterday, label)
    }

    @Test
    fun earlier_same_year_keeps_current_year_flag() {
        val label = conversationTimeLabel(Instant.parse("2026-01-03T12:00:00Z"), now, utc)
        val earlier = assertIs<ConversationTimeLabel.Earlier>(label)
        assertEquals(3, earlier.day)
        assertEquals(1, earlier.month)
        assertEquals(2026, earlier.year)
        assertEquals(true, earlier.currentYear)
    }

    @Test
    fun earlier_other_year_clears_current_year_flag() {
        val label = conversationTimeLabel(Instant.parse("2025-12-31T12:00:00Z"), now, utc)
        val earlier = assertIs<ConversationTimeLabel.Earlier>(label)
        assertEquals(false, earlier.currentYear)
        assertEquals(2025, earlier.year)
    }
}
