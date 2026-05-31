@file:OptIn(ExperimentalTime::class)

package com.bitchat.android.ui

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime

/**
 * KMP-compatible time formatting interface.
 * Replaces java.time.format.DateTimeFormatter for cross-platform compatibility.
 */
fun interface TimeFormatter {
    fun format(instant: Instant): String
}

/**
 * Default HH:mm:ss time formatter using kotlinx-datetime.
 * Platform-independent — no java.time dependencies.
 */
val HHmmssTimeFormatter: TimeFormatter = TimeFormatter { instant ->
    val localDateTime = Instant.fromEpochMilliseconds(instant.toEpochMilliseconds())
        .toLocalDateTime(TimeZone.currentSystemDefault())
    buildString {
        append(localDateTime.hour.toString().padStart(2, '0'))
        append(':')
        append(localDateTime.minute.toString().padStart(2, '0'))
        append(':')
        append(localDateTime.second.toString().padStart(2, '0'))
    }
}
