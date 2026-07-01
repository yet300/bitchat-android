package com.app.data.repository

import com.app.common.settings.SettingsStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeWriteMode
import kotlinx.coroutines.flow.Flow

/**
 * [SettingsStore] over a KSafe instance used in **plain** mode. This store holds only non-secret
 * preferences (theme, toggles, onboarding flags, mesh settings) — every write opts out of encryption
 * with [KSafeWriteMode.Plain]. Secrets never come here; they go through the crypto layer's encrypted
 * secure store. Synchronous reads/writes use KSafe's `*Direct` API (hot-cache reads, coalesced writes).
 */
@SingleIn(AppScope::class)
@Inject
internal class SettingsStoreImpl(
    private val prefs: KSafe,
) : SettingsStore {

    override fun getString(key: String, defaultValue: String): String = prefs.getDirect(key, defaultValue)

    override fun getStringOrNull(key: String): String? =
        if (prefs.getKeyInfo(key) == null) null else prefs.getDirect(key, "")

    override fun putString(key: String, value: String) = prefs.putDirect(key, value, PLAIN)

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = prefs.getDirect(key, defaultValue)

    override fun putBoolean(key: String, value: Boolean) = prefs.putDirect(key, value, PLAIN)

    override fun getInt(key: String, defaultValue: Int): Int = prefs.getDirect(key, defaultValue)

    override fun putInt(key: String, value: Int) = prefs.putDirect(key, value, PLAIN)

    override fun getLong(key: String, defaultValue: Long): Long = prefs.getDirect(key, defaultValue)

    override fun putLong(key: String, value: Long) = prefs.putDirect(key, value, PLAIN)

    override fun getDouble(key: String, defaultValue: Double): Double = prefs.getDirect(key, defaultValue)

    override fun putDouble(key: String, value: Double) = prefs.putDirect(key, value, PLAIN)

    override fun hasKey(key: String): Boolean = prefs.getKeyInfo(key) != null

    override fun remove(key: String) = prefs.deleteDirect(key)

    override fun getStringFlow(key: String, defaultValue: String): Flow<String> =
        prefs.getFlow(key, defaultValue)

    override fun getStringOrNullFlow(key: String): Flow<String?> =
        prefs.getFlow<String?>(key, null)

    override fun getBooleanFlow(key: String, defaultValue: Boolean): Flow<Boolean> =
        prefs.getFlow(key, defaultValue)

    private companion object {
        val PLAIN = KSafeWriteMode.Plain
    }
}
