package com.app.database.dao

import app.cash.turbine.test
import com.app.common.AppDispatchers
import com.app.database.Message
import com.app.database.TestDatabaseDriverFactory
import com.app.database.db.DatabaseManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * DAO round-trips on an in-memory database (no SQLCipher). Proves the schema/queries and the DAO layer
 * map as expected and that observers react to writes. The encryption-at-rest claim is proven separately
 * by the instrumented device test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DatabaseDaoTest {

    private lateinit var manager: DatabaseManager
    private lateinit var dispatchers: AppDispatchers

    @BeforeTest
    fun setUp() {
        dispatchers = AppDispatchers(io = UnconfinedTestDispatcher())
        manager = DatabaseManager(TestDatabaseDriverFactory(), dispatchers)
    }

    private fun message(id: String, conversation: String, timestamp: Long, mine: Boolean = false) =
        Message(
            id = id,
            conversation_id = conversation,
            sender_peer_id = "peer-$id",
            sender_name = "sender-$id",
            content = "content-$id",
            timestamp = timestamp,
            type = "TEXT",
            is_mine = if (mine) 1L else 0L,
            is_relay = 0L,
            mentions = null,
            delivery_status = null,
            attachment_path = null,
            attachment_type = null,
            pow_difficulty = null,
            channel = null,
            wire_json = null,
        )

    @Test
    fun messages_round_trip_ordered_and_deletable() = runTest {
        val dao = MessageDao(manager, dispatchers)
        dao.upsert(message("m2", "public", timestamp = 200))
        dao.upsert(message("m1", "public", timestamp = 100))

        assertEquals(listOf("m1", "m2"), dao.byConversation("public").map { it.id }) // timestamp ASC

        dao.updateDeliveryStatus("m1", "DELIVERED")
        assertEquals("DELIVERED", dao.byConversation("public").first { it.id == "m1" }.delivery_status)

        dao.deleteById("m1")
        assertEquals(listOf("m2"), dao.byConversation("public").map { it.id })
    }

    @Test
    fun messages_observer_reacts_to_writes() = runTest {
        val dao = MessageDao(manager, dispatchers)
        dao.observeByConversation("public").test {
            assertEquals(emptyList(), awaitItem())
            dao.upsert(message("m1", "public", timestamp = 100))
            assertEquals(listOf("m1"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun contacts_favorites_and_blocked() = runTest {
        val dao = ContactDao(manager, dispatchers)
        dao.upsert(
            com.app.database.Contact(
                fingerprint = "fp1",
                noise_key_hex = "deadbeef",
                nostr_pubkey = null,
                nickname = "carol",
                is_favorite = 1L,
                they_favorited_us = 0L,
                favorited_at = 5L,
                last_updated = 5L,
                is_blocked = 0L,
            ),
        )
        assertEquals(listOf("fp1"), dao.favorites().map { it.fingerprint })
        assertEquals(emptyList(), dao.blocked())

        dao.setBlocked("fp1", true)
        assertEquals(listOf("fp1"), dao.blocked())
        dao.setFavorite("fp1", false)
        assertEquals(emptyList(), dao.favorites())
    }

    @Test
    fun channels_geohash_and_secure_setting() = runTest {
        val channels = ChannelDao(manager, dispatchers)
        channels.upsert(
            com.app.database.Channel(
                tag = "#general",
                is_joined = 1L,
                is_password_protected = 0L,
                key_commitment = null,
                retention_policy = "KEEP_ALL",
            ),
        )
        assertEquals(listOf("#general"), channels.joined())

        val geo = GeohashDao(manager, dispatchers)
        geo.upsertBookmark("u4pruyd", 0L)
        assertEquals(listOf("u4pruyd"), geo.bookmarks())
        geo.insertBlocked("abc123")
        assertEquals(listOf("abc123"), geo.blocked())

        val settings = SecureSettingDao(manager, dispatchers)
        assertNull(settings.get("nickname"))
        settings.put("nickname", "dave")
        assertEquals("dave", settings.get("nickname"))
    }
}
