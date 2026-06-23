package com.app.data.repository

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.app.common.AppDispatchers
import com.app.common.settings.SettingsStore
import com.app.database.BitMessageDatabase
import com.app.database.dao.ChannelDao
import com.app.database.dao.ContactDao
import com.app.database.dao.ConversationDao
import com.app.database.dao.GeohashDao
import com.app.database.dao.MessageDao
import com.app.database.dao.SecureSettingDao
import com.app.database.db.DatabaseDriverFactory
import com.app.database.db.DatabaseManager
import com.app.common.settings.SettingsStore as Store
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.util.Properties

/**
 * A single in-memory database with DAOs over it, for repository round-trip tests. One JDBC `:memory:`
 * connection backs the whole instance, so DAOs (and repos sharing them) see the same data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class InMemoryDatabase(dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()) {

    private val dispatchers = AppDispatchers(io = dispatcher)

    private val factory = object : DatabaseDriverFactory {
        private val driver: SqlDriver =
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BitMessageDatabase.Schema)

        override suspend fun create(): SqlDriver = driver
    }

    val manager = DatabaseManager(factory, dispatchers)
    val messageDao = MessageDao(manager, dispatchers)
    val conversationDao = ConversationDao(manager, dispatchers)
    val contactDao = ContactDao(manager, dispatchers)
    val channelDao = ChannelDao(manager, dispatchers)
    val geohashDao = GeohashDao(manager, dispatchers)
    val secureSettingDao = SecureSettingDao(manager, dispatchers)
}

/** Minimal in-memory [SettingsStore] for repo tests; records every key written so tests can assert
 *  that no sensitive key ever lands in plaintext prefs. */
internal class FakeSettingsStore : Store {
    private val strings = MutableStateFlow<Map<String, String>>(emptyMap())
    private val bools = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Every string key that has been written (for "nothing sensitive in prefs" assertions). */
    val writtenStringKeys: Set<String> get() = strings.value.keys

    override fun getString(key: String, defaultValue: String): String = strings.value[key] ?: defaultValue
    override fun getStringOrNull(key: String): String? = strings.value[key]
    override fun putString(key: String, value: String) { strings.value = strings.value + (key to value) }
    override fun getStringFlow(key: String, defaultValue: String): Flow<String> = strings.map { it[key] ?: defaultValue }
    override fun getStringOrNullFlow(key: String): Flow<String?> = strings.map { it[key] }
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = bools.value[key] ?: defaultValue
    override fun putBoolean(key: String, value: Boolean) { bools.value = bools.value + (key to value) }
    override fun getBooleanFlow(key: String, defaultValue: Boolean): Flow<Boolean> = bools.map { it[key] ?: defaultValue }
    override fun getInt(key: String, defaultValue: Int) = defaultValue
    override fun putInt(key: String, value: Int) = Unit
    override fun getLong(key: String, defaultValue: Long) = defaultValue
    override fun putLong(key: String, value: Long) = Unit
    override fun getDouble(key: String, defaultValue: Double) = defaultValue
    override fun putDouble(key: String, value: Double) = Unit
    override fun hasKey(key: String) = strings.value.containsKey(key) || bools.value.containsKey(key)
    override fun remove(key: String) { strings.value = strings.value - key; bools.value = bools.value - key }
}
