@file:OptIn(ExperimentalTime::class)

package com.bitchat.android.core.domain.model

import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Статус доставки сообщения. Набор значений совпадает с текущим (и iOS) для поведенческой
 * нейтральности.
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
 * Бизнес-правило обновления статуса: статус НИКОГДА не понижается (например, Read не
 * откатывается до Delivered). Чистая, тестируемая политика (перенос из MessageManager.chooseStatus).
 */
object DeliveryStatusPolicy {

    private fun priority(status: DeliveryStatus?): Int = when (status) {
        null -> 0
        is DeliveryStatus.Sending -> 1
        is DeliveryStatus.Sent -> 2
        is DeliveryStatus.PartiallyDelivered -> 3
        is DeliveryStatus.Delivered -> 4
        is DeliveryStatus.Read -> 5
        is DeliveryStatus.Failed -> 0 // как и раньше — низший приоритет для порядка «галочек»
    }

    /** Возвращает статус с бОльшим приоритетом; при равенстве — новый. */
    fun merge(old: DeliveryStatus?, new: DeliveryStatus): DeliveryStatus =
        if (priority(new) >= priority(old)) new else old!!
}
