package com.bitchat.android.core.domain.repository

import com.bitchat.android.core.domain.model.Conversation
import com.bitchat.android.core.domain.model.ConversationId
import kotlinx.coroutines.flow.Flow

/**
 * Агрегат списка чатов (атрибут мессенджера) и непрочитанное.
 */
interface ConversationRepository {

    /** Поток списка диалогов (для экрана чатов). */
    fun observeConversations(): Flow<List<Conversation>>

    /** Суммарное число непрочитанных (для бейджа). */
    fun observeUnreadCount(): Flow<Int>

    /** Пометить диалог прочитанным (сбросить локальный счётчик непрочитанного). */
    suspend fun markRead(id: ConversationId)
}
