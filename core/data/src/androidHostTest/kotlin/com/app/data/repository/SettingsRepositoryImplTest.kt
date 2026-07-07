package com.app.data.repository

import com.app.transport.NicknameHolder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryImplTest {

    @Test
    fun nickname_round_trips_through_db_not_prefs() = runTest {
        val db = InMemoryDatabase()
        val settings = FakeSettingsStore()
        val repo = SettingsRepositoryImpl(db.secureSettingDao, settings, NicknameHolder())

        assertEquals("", repo.observeNickname().first())

        repo.setNickname("agent-smith")
        assertEquals("agent-smith", repo.observeNickname().first())

        // The nickname must never land in plaintext prefs; it lives in the encrypted DB.
        assertFalse("nickname" in settings.writtenStringKeys)
        assertEquals("agent-smith", db.secureSettingDao.get("nickname"))
    }

    @Test
    fun setNickname_pushes_into_transport_holder_synchronously() = runTest {
        val holder = NicknameHolder()
        val repo = SettingsRepositoryImpl(InMemoryDatabase().secureSettingDao, FakeSettingsStore(), holder)

        assertEquals("anon1234", holder.nickname("anon1234"))
        repo.setNickname("Alice")
        // No flow settling: the announce fired right after setNickname must already see "Alice".
        assertEquals("Alice", holder.nickname("anon1234"))
    }

    @Test
    fun location_flag_stays_in_prefs() = runTest {
        val repo = SettingsRepositoryImpl(
            InMemoryDatabase().secureSettingDao,
            FakeSettingsStore(),
            NicknameHolder(),
        )
        repo.locationServicesEnabled = false
        assertFalse(repo.locationServicesEnabled)
    }

    @Test
    fun nicknameSync_migrates_plaintext_key_and_populates_holder() = runTest(UnconfinedTestDispatcher()) {
        val db = InMemoryDatabase(UnconfinedTestDispatcher(testScheduler))
        val settings = FakeSettingsStore().apply { putString("nickname", "leaked-copy") }
        val holder = NicknameHolder()
        db.secureSettingDao.put("nickname", "Alice")

        val sync = NicknameSync(db.secureSettingDao, settings, holder, backgroundScope)
        sync.start()
        runCurrent()

        // One-time migration: the legacy plaintext copy is gone (and stays gone on re-run).
        assertFalse(settings.hasKey("nickname"))
        sync.start()
        runCurrent()
        assertFalse(settings.hasKey("nickname"))

        // The persisted encrypted value reached the transport holder.
        assertEquals("Alice", holder.nickname("anon1234"))

        // Later persisted changes keep flowing into the holder.
        db.secureSettingDao.put("nickname", "Bob")
        runCurrent()
        assertEquals("Bob", holder.nickname("anon1234"))
    }

    @Test
    fun nicknameSync_leaves_holder_on_fallback_when_nothing_persisted() = runTest(UnconfinedTestDispatcher()) {
        val db = InMemoryDatabase(UnconfinedTestDispatcher(testScheduler))
        val holder = NicknameHolder()

        NicknameSync(db.secureSettingDao, FakeSettingsStore(), holder, backgroundScope).start()
        runCurrent()

        assertEquals("anon1234", holder.nickname("anon1234"))
    }
}
