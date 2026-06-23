package com.app.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsRepositoryImplTest {

    @Test
    fun nickname_round_trips_through_db_not_prefs() = runTest {
        val db = InMemoryDatabase()
        val settings = FakeSettingsStore()
        val repo = SettingsRepositoryImpl(db.secureSettingDao, settings)

        assertEquals("", repo.observeNickname().first())

        repo.setNickname("agent-smith")
        assertEquals("agent-smith", repo.observeNickname().first())

        // The nickname must never land in plaintext prefs; it lives in the encrypted DB.
        assertFalse("nickname" in settings.writtenStringKeys)
        assertEquals("agent-smith", db.secureSettingDao.get("nickname"))
    }

    @Test
    fun location_flag_stays_in_prefs() = runTest {
        val repo = SettingsRepositoryImpl(InMemoryDatabase().secureSettingDao, FakeSettingsStore())
        repo.locationServicesEnabled = false
        assertFalse(repo.locationServicesEnabled)
    }
}
