@file:OptIn(ExperimentalTime::class)

package com.bitchat.android.core.domain.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Message delivery status. The set of values matches the current (and iOS) one for behavioral
 * neutrality.
 */
sealed interface DeliveryStatus {
    data object Sending : DeliveryStatus
    data object Sent : DeliveryStatus
    data class Delivered(val to: String, val at: Instant) : DeliveryStatus
    data class Read(val by: String, val at: Instant) : DeliveryStatus
    data class Failed(val reason: String) : DeliveryStatus
    data class PartiallyDelivered(val reached: Int, val total: Int) : DeliveryStatus
}

/**
 * Status-update business rule: the status is NEVER downgraded (e.g. Read does not revert to
 * Delivered). Pure, testable policy (ported from MessageManager.chooseStatus).
 */
object DeliveryStatusPolicy {

    private fun priority(status: DeliveryStatus?): Int = when (status) {
        null -> 0
        is DeliveryStatus.Sending -> 1
        is DeliveryStatus.Sent -> 2
        is DeliveryStatus.PartiallyDelivered -> 3
        is DeliveryStatus.Delivered -> 4
        is DeliveryStatus.Read -> 5
        is DeliveryStatus.Failed -> 0 // as before — lowest priority for check-mark ordering
    }

    /** Returns the higher-priority status; on a tie, the new one. */
    fun merge(old: DeliveryStatus?, new: DeliveryStatus): DeliveryStatus =
        if (priority(new) >= priority(old)) new else old!!
}
