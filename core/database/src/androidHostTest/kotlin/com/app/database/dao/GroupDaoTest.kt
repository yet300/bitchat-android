package com.app.database.dao

import com.app.common.AppDispatchers
import com.app.database.TestDatabaseDriverFactory
import com.app.database.db.DatabaseManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * GroupDao raw CRUD: insert, in-place update (preserving created_at + creator_fingerprint),
 * select ordering by created_at, delete, and wipe. The DAO is transport-free — roster is any blob.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupDaoTest {

    private lateinit var manager: DatabaseManager
    private lateinit var dispatchers: AppDispatchers
    private lateinit var dao: GroupDao

    private fun id(n: Int) = ByteArray(16) { n.toByte() }
    private fun fp(n: Int) = ByteArray(32) { (n + it).toByte() }
    private fun roster(n: Int) = ByteArray(8) { (0xA0 + n + it).toByte() }
    private fun key(n: Int) = ByteArray(32) { (0x40 + n + it).toByte() }

    @BeforeTest
    fun setUp() {
        dispatchers = AppDispatchers(io = UnconfinedTestDispatcher())
        manager = DatabaseManager(TestDatabaseDriverFactory(), dispatchers)
        dao = GroupDao(manager, dispatchers)
    }

    @Test
    fun insert_then_select_round_trips() = runTest {
        dao.upsert(id(1), "grp", epoch = 1, creatorFingerprint = fp(1), roster = roster(1), groupKey = key(1), nowMs = 100)
        val row = dao.selectById(id(1))!!
        assertEquals("grp", row.name)
        assertEquals(1L, row.epoch)
        assertContentEquals(fp(1), row.creator_fingerprint)
        assertContentEquals(roster(1), row.roster)
        assertContentEquals(key(1), row.group_key)
        assertEquals(100L, row.created_at)
    }

    @Test
    fun update_in_place_preserves_created_at_and_creator() = runTest {
        dao.upsert(id(1), "grp", epoch = 1, creatorFingerprint = fp(1), roster = roster(1), groupKey = key(1), nowMs = 100)
        // Rotate: new epoch/roster/key at a later time — created_at and creator must not move.
        dao.upsert(id(1), "grp2", epoch = 2, creatorFingerprint = fp(9), roster = roster(2), groupKey = key(2), nowMs = 500)
        val row = dao.selectById(id(1))!!
        assertEquals("grp2", row.name)
        assertEquals(2L, row.epoch)
        assertContentEquals(roster(2), row.roster)
        assertContentEquals(key(2), row.group_key)
        assertEquals(100L, row.created_at, "created_at preserved across update")
        assertContentEquals(fp(1), row.creator_fingerprint, "creator never changes")
        assertEquals(1, dao.selectAll().size)
    }

    @Test
    fun select_all_orders_by_created_at() = runTest {
        dao.upsert(id(2), "b", epoch = 1, creatorFingerprint = fp(2), roster = roster(2), groupKey = key(2), nowMs = 200)
        dao.upsert(id(1), "a", epoch = 1, creatorFingerprint = fp(1), roster = roster(1), groupKey = key(1), nowMs = 100)
        val names = dao.selectAll().map { it.name }
        assertContentEquals(listOf("a", "b"), names)
    }

    @Test
    fun delete_by_id_removes_one() = runTest {
        dao.upsert(id(1), "a", epoch = 1, creatorFingerprint = fp(1), roster = roster(1), groupKey = key(1), nowMs = 100)
        dao.upsert(id(2), "b", epoch = 1, creatorFingerprint = fp(2), roster = roster(2), groupKey = key(2), nowMs = 200)
        dao.deleteById(id(1))
        assertNull(dao.selectById(id(1)))
        assertEquals(1, dao.selectAll().size)
    }

    @Test
    fun wipe_drops_all() = runTest {
        dao.upsert(id(1), "a", epoch = 1, creatorFingerprint = fp(1), roster = roster(1), groupKey = key(1), nowMs = 100)
        dao.upsert(id(2), "b", epoch = 1, creatorFingerprint = fp(2), roster = roster(2), groupKey = key(2), nowMs = 200)
        dao.wipe()
        assertEquals(0, dao.selectAll().size)
    }
}
