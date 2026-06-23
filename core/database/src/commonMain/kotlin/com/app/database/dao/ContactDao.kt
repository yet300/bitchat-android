package com.app.database.dao

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.app.common.AppDispatchers
import com.app.database.Contact
import com.app.database.db.DatabaseManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/** Peer favorites / blocked / fingerprints. Returns the generated [Contact] entity. */
class ContactDao(
    private val databaseManager: DatabaseManager,
    private val dispatchers: AppDispatchers,
) {

    suspend fun favorites(): List<Contact> = withContext(dispatchers.io) {
        databaseManager.getDb().contactQueries.selectFavorites().executeAsList()
    }

    fun observeFavorites(): Flow<List<Contact>> = flow {
        val db = databaseManager.getDb()
        emitAll(db.contactQueries.selectFavorites().asFlow().mapToList(dispatchers.io))
    }

    suspend fun blocked(): List<String> = withContext(dispatchers.io) {
        databaseManager.getDb().contactQueries.selectBlocked().executeAsList()
    }

    fun observeBlocked(): Flow<List<String>> = flow {
        val db = databaseManager.getDb()
        emitAll(db.contactQueries.selectBlocked().asFlow().mapToList(dispatchers.io))
    }

    suspend fun byFingerprint(fingerprint: String): Contact? = withContext(dispatchers.io) {
        databaseManager.getDb().contactQueries.selectByFingerprint(fingerprint).executeAsOneOrNull()
    }

    suspend fun upsert(contact: Contact) = withContext(dispatchers.io) {
        databaseManager.getDb().contactQueries.upsert(
            fingerprint = contact.fingerprint,
            noise_key_hex = contact.noise_key_hex,
            nostr_pubkey = contact.nostr_pubkey,
            nickname = contact.nickname,
            is_favorite = contact.is_favorite,
            they_favorited_us = contact.they_favorited_us,
            favorited_at = contact.favorited_at,
            last_updated = contact.last_updated,
            is_blocked = contact.is_blocked,
        )
    }

    suspend fun setFavorite(fingerprint: String, isFavorite: Boolean) = withContext(dispatchers.io) {
        databaseManager.getDb().contactQueries.setFavorite(isFavorite.toLong(), fingerprint)
    }

    suspend fun setBlocked(fingerprint: String, isBlocked: Boolean) = withContext(dispatchers.io) {
        databaseManager.getDb().contactQueries.setBlocked(isBlocked.toLong(), fingerprint)
    }

    suspend fun deleteAll() = withContext(dispatchers.io) {
        databaseManager.getDb().contactQueries.deleteAll()
    }

    private fun Boolean.toLong(): Long = if (this) 1L else 0L
}
