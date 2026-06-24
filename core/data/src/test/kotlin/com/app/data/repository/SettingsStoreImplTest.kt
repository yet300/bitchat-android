package com.app.data.repository

import androidx.test.core.app.ApplicationProvider
import com.app.common.settings.SettingsStore
import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeProtectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Round-trips every [SettingsStore] type through the real KSafe-backed [SettingsStoreImpl] in plain
 * mode. Plain mode needs no Android Keystore, so it runs on the host JVM (Robolectric). Also asserts
 * the writes are NOT encrypted — this store must never hold secrets.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsStoreImplTest {

    private lateinit var prefs: KSafe
    private lateinit var store: SettingsStore

    @Before
    fun setup() {
        prefs = KSafe(
            ApplicationProvider.getApplicationContext<android.content.Context>().applicationContext,
            fileName = "settings_store_test",
        )
        store = SettingsStoreImpl(prefs)
    }

    @Test
    fun roundTripsEveryType() {
        store.putString("s", "hello")
        store.putBoolean("b", true)
        store.putInt("i", 42)
        store.putLong("l", 9_000_000_000L)
        store.putDouble("d", 3.5)

        assertEquals("hello", store.getString("s", ""))
        assertTrue(store.getBoolean("b", false))
        assertEquals(42, store.getInt("i", 0))
        assertEquals(9_000_000_000L, store.getLong("l", 0L))
        assertEquals(3.5, store.getDouble("d", 0.0), 0.0)
    }

    @Test
    fun stringOrNull_hasKey_remove() {
        assertNull(store.getStringOrNull("missing"))
        assertFalse(store.hasKey("missing"))

        store.putString("k", "v")
        assertEquals("v", store.getStringOrNull("k"))
        assertTrue(store.hasKey("k"))

        store.remove("k")
        assertNull(store.getStringOrNull("k"))
        assertFalse(store.hasKey("k"))
    }

    @Test
    fun writesAreNotEncrypted() {
        store.putString("plain_key", "not-a-secret")
        // A plain write must not be hardware/keystore-backed — this store never holds secrets.
        val level = prefs.getKeyInfo("plain_key")?.level
        assertTrue(
            "Plain write should not be keystore-backed, got $level",
            level == null || level < KSafeProtectionLevel.HARDWARE_BACKED,
        )
    }
}
