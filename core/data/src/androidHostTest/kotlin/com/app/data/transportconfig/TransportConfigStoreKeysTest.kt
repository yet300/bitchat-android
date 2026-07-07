package com.app.data.transportconfig

import com.app.common.settings.SettingsStore
import com.app.transport.net.TorMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden-key contract for the transport config port implementations: the settings keys and the
 * effective defaults must match the pre-port *PreferenceManager code byte-for-byte, because they
 * are already written on installed devices and there is no migration path.
 */
class TransportConfigStoreKeysTest {

    private class RecordingSettingsStore : SettingsStore {
        val backing = mutableMapOf<String, Any>()
        val removed = mutableListOf<String>()

        override fun getString(key: String, defaultValue: String): String = backing[key] as? String ?: defaultValue
        override fun getStringOrNull(key: String): String? = backing[key] as? String
        override fun putString(key: String, value: String) { backing[key] = value }
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean = backing[key] as? Boolean ?: defaultValue
        override fun putBoolean(key: String, value: Boolean) { backing[key] = value }
        override fun getInt(key: String, defaultValue: Int): Int = backing[key] as? Int ?: defaultValue
        override fun putInt(key: String, value: Int) { backing[key] = value }
        override fun getLong(key: String, defaultValue: Long): Long = backing[key] as? Long ?: defaultValue
        override fun putLong(key: String, value: Long) { backing[key] = value }
        override fun getDouble(key: String, defaultValue: Double): Double = backing[key] as? Double ?: defaultValue
        override fun putDouble(key: String, value: Double) { backing[key] = value }
        override fun hasKey(key: String): Boolean = backing.containsKey(key)
        override fun remove(key: String) { backing.remove(key); removed += key }
        override fun getStringFlow(key: String, defaultValue: String): Flow<String> = flowOf(getString(key, defaultValue))
        override fun getStringOrNullFlow(key: String): Flow<String?> = flowOf(getStringOrNull(key))
        override fun getBooleanFlow(key: String, defaultValue: Boolean): Flow<Boolean> = flowOf(getBoolean(key, defaultValue))
    }

    @Test
    fun `key names match the legacy preference managers`() {
        assertEquals("tor_mode", TransportConfigKeys.TOR_MODE)
        assertEquals("pow_enabled", TransportConfigKeys.POW_ENABLED)
        assertEquals("pow_difficulty", TransportConfigKeys.POW_DIFFICULTY)
        assertEquals("last_update_ms", TransportConfigKeys.RELAY_LAST_UPDATE_MS)
        assertEquals("geohash_alias_registry", TransportConfigKeys.GEOHASH_ALIAS_REGISTRY)
        assertEquals("geohash_conversation_registry", TransportConfigKeys.GEOHASH_CONVERSATION_REGISTRY)
        assertEquals("verbose_logging", TransportConfigKeys.VERBOSE_LOGGING)
        assertEquals("gatt_server_enabled", TransportConfigKeys.GATT_SERVER_ENABLED)
        assertEquals("gatt_client_enabled", TransportConfigKeys.GATT_CLIENT_ENABLED)
        assertEquals("packet_relay_enabled", TransportConfigKeys.PACKET_RELAY_ENABLED)
        assertEquals("max_connections_overall", TransportConfigKeys.MAX_CONNECTIONS_OVERALL)
        assertEquals("max_connections_server", TransportConfigKeys.MAX_CONNECTIONS_SERVER)
        assertEquals("max_connections_client", TransportConfigKeys.MAX_CONNECTIONS_CLIENT)
        assertEquals("seen_packet_capacity", TransportConfigKeys.SEEN_PACKET_CAPACITY)
        assertEquals("gcs_max_filter_bytes", TransportConfigKeys.GCS_MAX_FILTER_BYTES)
        assertEquals("gcs_filter_fpr_percent", TransportConfigKeys.GCS_FILTER_FPR_PERCENT)
    }

    @Test
    fun `tor store reads and writes tor_mode with valueOf fallback`() {
        val settings = RecordingSettingsStore()
        val store = SettingsTorConfigStore(settings)

        assertEquals(TorMode.OFF, store.getTorMode(TorMode.OFF))
        store.setTorMode(TorMode.ON)
        assertEquals("ON", settings.backing["tor_mode"])
        assertEquals(TorMode.ON, store.getTorMode(TorMode.OFF))

        settings.backing["tor_mode"] = "garbage"
        assertEquals(TorMode.OFF, store.getTorMode(TorMode.OFF))
    }

    @Test
    fun `nostr store uses the legacy keys`() {
        val settings = RecordingSettingsStore()
        val store = SettingsNostrConfigStore(settings)

        assertEquals(false, store.getPowEnabled(false))
        store.setPowEnabled(true)
        assertEquals(true, settings.backing["pow_enabled"])

        assertEquals(12, store.getPowDifficulty(12))
        store.setPowDifficulty(16)
        assertEquals(16, settings.backing["pow_difficulty"])

        assertEquals(0L, store.getRelayLastUpdateMs(0L))
        store.setRelayLastUpdateMs(123L)
        assertEquals(123L, settings.backing["last_update_ms"])

        assertNull(store.getAliasRegistryJson())
        store.setAliasRegistryJson("{}")
        assertEquals("{}", settings.backing["geohash_alias_registry"])
        store.clearAliasRegistryJson()
        assertTrue("geohash_alias_registry" in settings.removed)

        assertNull(store.getConversationRegistryJson())
        store.setConversationRegistryJson("{}")
        assertEquals("{}", settings.backing["geohash_conversation_registry"])
        store.clearConversationRegistryJson()
        assertTrue("geohash_conversation_registry" in settings.removed)
    }

    @Test
    fun `debug store uses the legacy keys and passes caller defaults through`() {
        val settings = RecordingSettingsStore()
        val store = SettingsDebugConfigStore(settings)

        // Empty store: the caller-supplied defaults win (toggles default-on at the call sites).
        assertEquals(false, store.getVerboseLogging(false))
        assertEquals(true, store.getGattServerEnabled(true))
        assertEquals(true, store.getGattClientEnabled(true))
        assertEquals(true, store.getPacketRelayEnabled(true))
        assertEquals(8, store.getMaxConnectionsOverall(8))
        assertEquals(500, store.getSeenPacketCapacity(500))
        assertEquals(400, store.getGcsMaxFilterBytes(400))
        assertEquals(1.0, store.getGcsFprPercent(1.0), 0.0)

        store.setVerboseLogging(true)
        store.setGattServerEnabled(false)
        store.setGattClientEnabled(false)
        store.setPacketRelayEnabled(false)
        store.setMaxConnectionsOverall(4)
        store.setMaxConnectionsServer(5)
        store.setMaxConnectionsClient(6)
        store.setSeenPacketCapacity(100)
        store.setGcsMaxFilterBytes(200)
        store.setGcsFprPercent(2.5)

        assertEquals(
            mapOf(
                "verbose_logging" to true,
                "gatt_server_enabled" to false,
                "gatt_client_enabled" to false,
                "packet_relay_enabled" to false,
                "max_connections_overall" to 4,
                "max_connections_server" to 5,
                "max_connections_client" to 6,
                "seen_packet_capacity" to 100,
                "gcs_max_filter_bytes" to 200,
                "gcs_filter_fpr_percent" to 2.5,
            ),
            settings.backing.toMap(),
        )
    }
}
