package com.bitchat.android.core.domain.repository

import com.bitchat.android.core.domain.model.GeohashChannel
import com.bitchat.android.core.domain.model.PeerId

/**
 * Порт исходящего транспорта. Скрывает выбор mesh⇄Nostr (текущий MessageRouter), outbox и
 * адресацию — это инфраструктурная деталь. Domain просто «отправляет».
 */
interface MessageTransport {

    /** Публичное/канальное сообщение (channel == null → общий mesh-чат). */
    suspend fun sendPublic(content: String, mentions: List<String>, channel: String?)

    /** Личное сообщение. Маршрут (mesh/Nostr) выбирает реализация. */
    suspend fun sendPrivate(content: String, to: PeerId, recipientNickname: String?, messageId: String)

    /** Сообщение в гео-чат (Nostr ephemeral event). */
    suspend fun sendGeohash(content: String, channel: GeohashChannel, nickname: String?)

    /** Квитанция прочтения. */
    suspend fun sendReadReceipt(messageId: String, to: PeerId)

    /** Уведомление о (раз)избранном — рассылается mesh или Nostr. */
    suspend fun sendFavoriteNotification(to: PeerId, isFavorite: Boolean)

    /** Анонс себя в сеть (например, после смены ника). */
    suspend fun announceSelf()
}
