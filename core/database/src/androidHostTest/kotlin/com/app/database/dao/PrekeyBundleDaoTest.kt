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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PrekeyBundleDao resource and reuse rules, mirroring the reference iOS `PrekeyBundleStore`:
 * strictly-newer ingest, consumption carry across replacements, per-message assignment reuse,
 * lowest-unused-first assignment, 7-day sealing freshness, and the 200-owner LRU cap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrekeyBundleDaoTest {

    private lateinit var dao: PrekeyBundleDao

    private val now = 1_000_000_000L

    private fun owner(n: Int) = ByteArray(32) { (n + it).toByte() }
    private fun pub(n: Int) = ByteArray(32) { (0x40 + n + it).toByte() }
    private fun prekeys(vararg ids: Int) = ids.map { it.toUInt() to pub(it) }

    @BeforeTest
    fun setUp() {
        val dispatchers = AppDispatchers(io = UnconfinedTestDispatcher())
        dao = PrekeyBundleDao(DatabaseManager(TestDatabaseDriverFactory(), dispatchers), dispatchers)
    }

    @Test
    fun ingest_rejects_equal_or_older_generatedAt() = runTest {
        assertTrue(dao.ingest(owner(1), 100L, prekeys(0, 1), now))
        assertFalse(dao.ingest(owner(1), 100L, prekeys(0, 1, 2), now), "equal generatedAt rejected")
        assertFalse(dao.ingest(owner(1), 99L, prekeys(3), now), "older generatedAt rejected")
        assertTrue(dao.ingest(owner(1), 101L, prekeys(0, 1, 2), now), "strictly newer accepted")
    }

    @Test
    fun ingest_rejects_empty_bundles() = runTest {
        assertFalse(dao.ingest(owner(1), 100L, emptyList(), now))
    }

    @Test
    fun assign_uses_lowest_unused_id_and_marks_it_used() = runTest {
        dao.ingest(owner(1), now, prekeys(5, 3, 7), now)

        val first = dao.assignPrekey("msg-a", owner(1), now)
        assertNotNull(first)
        assertEquals(3u, first.id)
        assertContentEquals(pub(3), first.publicKey)

        val second = dao.assignPrekey("msg-b", owner(1), now)
        assertEquals(5u, second!!.id)
    }

    @Test
    fun assign_reuses_the_message_assignment_on_redeposit() = runTest {
        dao.ingest(owner(1), now, prekeys(0, 1), now)
        val first = dao.assignPrekey("msg-a", owner(1), now)!!
        val again = dao.assignPrekey("msg-a", owner(1), now)!!
        assertEquals(first.id, again.id, "re-deposit shares the message's prekey")
        assertEquals(1u, dao.assignPrekey("msg-b", owner(1), now)!!.id)
    }

    @Test
    fun assign_returns_null_when_all_prekeys_are_spent() = runTest {
        dao.ingest(owner(1), now, prekeys(0), now)
        assertNotNull(dao.assignPrekey("msg-a", owner(1), now))
        assertNull(dao.assignPrekey("msg-b", owner(1), now), "exhausted bundle falls back to v1")
        assertFalse(dao.hasUsableBundle(owner(1), now))
    }

    @Test
    fun assign_returns_null_for_unknown_owner() = runTest {
        assertNull(dao.assignPrekey("msg-a", owner(9), now))
        assertFalse(dao.hasUsableBundle(owner(9), now))
    }

    @Test
    fun stale_bundle_is_not_sealed_to() = runTest {
        val generated = now - PrekeyBundleDao.Limits.MAX_BUNDLE_AGE_FOR_SEALING_MS - 1
        dao.ingest(owner(1), generated, prekeys(0), now)
        assertNull(dao.assignPrekey("msg-a", owner(1), now), "7d-stale bundle unusable")
        assertFalse(dao.hasUsableBundle(owner(1), now))
    }

    @Test
    fun replace_carries_consumption_for_surviving_ids() = runTest {
        // Bundles carry a real ms-since-epoch generatedAt near now (the 7d sealing window applies).
        dao.ingest(owner(1), now, prekeys(0, 1, 2), now)
        assertEquals(0u, dao.assignPrekey("msg-a", owner(1), now)!!.id)

        // Top-up keeps ids 1,2 and adds 3; consumed id 0 is dropped by the owner.
        assertTrue(dao.ingest(owner(1), now + 1, prekeys(1, 2, 3), now))
        assertEquals(1u, dao.assignPrekey("msg-b", owner(1), now)!!.id)

        // The assignment for msg-a pointed at id 0, which the fresh bundle dropped —
        // a re-deposit of msg-a burns a new prekey instead of resurrecting id 0.
        assertEquals(2u, dao.assignPrekey("msg-a", owner(1), now)!!.id)
    }

    @Test
    fun replace_keeps_used_marks_and_their_assignments() = runTest {
        dao.ingest(owner(1), now, prekeys(0, 1), now)
        val assigned = dao.assignPrekey("msg-a", owner(1), now)!!
        assertEquals(0u, assigned.id)

        assertTrue(dao.ingest(owner(1), now + 1, prekeys(0, 1, 2), now))
        // id 0 stays used AND keeps its message binding across the replace.
        assertEquals(0u, dao.assignPrekey("msg-a", owner(1), now)!!.id)
        assertEquals(1u, dao.assignPrekey("msg-b", owner(1), now)!!.id)
    }

    @Test
    fun owner_cap_evicts_least_recently_updated_first() = runTest {
        for (n in 0 until PrekeyBundleDao.Limits.MAX_PEERS) {
            assertTrue(dao.ingest(owner(n), now, prekeys(0), now + n))
        }
        assertEquals(PrekeyBundleDao.Limits.MAX_PEERS.toLong(), dao.bundleCount())

        // One more owner evicts the least recently updated (owner 0).
        assertTrue(dao.ingest(owner(1000), now, prekeys(0), now + 5000))
        assertEquals(PrekeyBundleDao.Limits.MAX_PEERS.toLong(), dao.bundleCount())
        assertFalse(dao.hasUsableBundle(owner(0), now))
        assertTrue(dao.hasUsableBundle(owner(1), now))
    }

    @Test
    fun replacing_a_known_owner_never_trips_the_cap() = runTest {
        for (n in 0 until PrekeyBundleDao.Limits.MAX_PEERS) {
            dao.ingest(owner(n), 100L, prekeys(0), now + n)
        }
        assertTrue(dao.ingest(owner(3), 101L, prekeys(0, 1), now + 5000))
        assertEquals(PrekeyBundleDao.Limits.MAX_PEERS.toLong(), dao.bundleCount())
    }

    @Test
    fun wipe_drops_everything() = runTest {
        dao.ingest(owner(1), now, prekeys(0, 1), now)
        dao.wipe()
        assertEquals(0L, dao.bundleCount())
        assertNull(dao.assignPrekey("msg-a", owner(1), now))
    }
}
