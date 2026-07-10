package com.app.database.dao

import com.app.common.AppDispatchers
import com.app.database.TestDatabaseDriverFactory
import com.app.database.db.DatabaseManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** VouchDao round-trips: per-vouchee cap, oldest-only eviction, and timestamp refresh. */
@OptIn(ExperimentalCoroutinesApi::class)
class VouchDaoTest {

    private lateinit var manager: DatabaseManager
    private lateinit var dispatchers: AppDispatchers
    private lateinit var dao: VouchDao

    private val vouchee = "e".repeat(64)

    private fun voucher(n: Int) = n.toString(16).padStart(2, '0').repeat(32)

    @BeforeTest
    fun setUp() {
        dispatchers = AppDispatchers(io = UnconfinedTestDispatcher())
        manager = DatabaseManager(TestDatabaseDriverFactory(), dispatchers)
        dao = VouchDao(manager, dispatchers)
    }

    @Test
    fun records_and_reads_back_newest_first() = runTest {
        assertTrue(dao.record(vouchee, voucher(1), timestampMs = 100))
        assertTrue(dao.record(vouchee, voucher(2), timestampMs = 300))
        assertTrue(dao.record(vouchee, voucher(3), timestampMs = 200))

        val records = dao.vouchersFor(vouchee)
        assertEquals(listOf(voucher(2), voucher(3), voucher(1)), records.map { it.voucherFingerprint })
    }

    @Test
    fun same_voucher_refreshes_to_the_newer_timestamp_and_never_regresses() = runTest {
        assertTrue(dao.record(vouchee, voucher(1), timestampMs = 500))
        assertTrue(dao.record(vouchee, voucher(1), timestampMs = 900))
        assertEquals(900, dao.vouchersFor(vouchee).single().timestampMs)

        // A stale replay does not regress the stored timestamp.
        assertTrue(dao.record(vouchee, voucher(1), timestampMs = 100))
        assertEquals(900, dao.vouchersFor(vouchee).single().timestampMs)
        assertEquals(1, dao.vouchersFor(vouchee).size)
    }

    @Test
    fun caps_at_eight_vouchers_keeping_the_most_recent() = runTest {
        for (i in 1..8) assertTrue(dao.record(vouchee, voucher(i), timestampMs = i * 100L))
        assertEquals(8, dao.vouchersFor(vouchee).size)

        // A newer vouch evicts the oldest (voucher 1 @ 100).
        assertTrue(dao.record(vouchee, voucher(9), timestampMs = 900))
        val fps = dao.vouchersFor(vouchee).map { it.voucherFingerprint }
        assertEquals(8, fps.size)
        assertFalse(voucher(1) in fps)
        assertTrue(voucher(9) in fps)
    }

    @Test
    fun full_of_fresher_vouches_rejects_an_older_newcomer() = runTest {
        for (i in 1..8) assertTrue(dao.record(vouchee, voucher(i), timestampMs = i * 100L))

        // Older than every stored voucher → nothing changes.
        assertFalse(dao.record(vouchee, voucher(9), timestampMs = 50))
        assertEquals(8, dao.vouchersFor(vouchee).size)
        assertFalse(voucher(9) in dao.vouchersFor(vouchee).map { it.voucherFingerprint })
    }

    @Test
    fun a_tie_with_the_oldest_is_rejected_so_the_outcome_is_deterministic() = runTest {
        for (i in 1..8) assertTrue(dao.record(vouchee, voucher(i), timestampMs = 100))
        // Equal to the oldest timestamp → not strictly newer → rejected.
        assertFalse(dao.record(vouchee, voucher(9), timestampMs = 100))
        assertEquals(8, dao.vouchersFor(vouchee).size)
    }

    @Test
    fun deletes_scope_correctly() = runTest {
        dao.record(vouchee, voucher(1), timestampMs = 100)
        dao.record("f".repeat(64), voucher(2), timestampMs = 100)

        dao.deleteByVouchee(vouchee)
        assertTrue(dao.vouchersFor(vouchee).isEmpty())
        assertEquals(1, dao.vouchersFor("f".repeat(64)).size)

        dao.deleteAll()
        assertTrue(dao.allByVouchee().isEmpty())
    }
}
