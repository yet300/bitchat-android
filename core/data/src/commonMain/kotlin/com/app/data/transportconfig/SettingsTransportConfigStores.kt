package com.app.data.transportconfig

import com.app.common.settings.SettingsStore
import com.app.transport.debug.DebugConfigStore
import com.app.transport.net.TorConfigStore
import com.app.transport.net.TorMode
import com.app.transport.nostr.NostrConfigStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * [SettingsStore]-backed implementations of the transport config ports ([TorConfigStore],
 * [NostrConfigStore], [DebugConfigStore]). The key names predate the ports and are shared with
 * installed devices — they must never change (no migration path). [TransportConfigKeys] holds them
 * so the golden-key unit test can assert the on-disk contract.
 */
internal object TransportConfigKeys {
    // Tor
    const val TOR_MODE = "tor_mode"

    // Nostr
    const val POW_ENABLED = "pow_enabled"
    const val POW_DIFFICULTY = "pow_difficulty"
    const val RELAY_LAST_UPDATE_MS = "last_update_ms"
    const val GEOHASH_ALIAS_REGISTRY = "geohash_alias_registry"
    const val GEOHASH_CONVERSATION_REGISTRY = "geohash_conversation_registry"

    // Mesh debug
    const val VERBOSE_LOGGING = "verbose_logging"
    const val GATT_SERVER_ENABLED = "gatt_server_enabled"
    const val GATT_CLIENT_ENABLED = "gatt_client_enabled"
    const val PACKET_RELAY_ENABLED = "packet_relay_enabled"
    const val MAX_CONNECTIONS_OVERALL = "max_connections_overall"
    const val MAX_CONNECTIONS_SERVER = "max_connections_server"
    const val MAX_CONNECTIONS_CLIENT = "max_connections_client"
    const val SEEN_PACKET_CAPACITY = "seen_packet_capacity"
    const val GCS_MAX_FILTER_BYTES = "gcs_max_filter_bytes"
    const val GCS_FILTER_FPR_PERCENT = "gcs_filter_fpr_percent"
}

@SingleIn(AppScope::class)
@Inject
internal class SettingsTorConfigStore(
    private val settings: SettingsStore,
) : TorConfigStore {

    override fun getTorMode(default: TorMode): TorMode {
        val saved = settings.getString(TransportConfigKeys.TOR_MODE, default.name)
        return runCatching { TorMode.valueOf(saved) }.getOrDefault(default)
    }

    override fun setTorMode(mode: TorMode) {
        settings.putString(TransportConfigKeys.TOR_MODE, mode.name)
    }
}

@SingleIn(AppScope::class)
@Inject
internal class SettingsNostrConfigStore(
    private val settings: SettingsStore,
) : NostrConfigStore {

    override fun getPowEnabled(default: Boolean): Boolean =
        settings.getBoolean(TransportConfigKeys.POW_ENABLED, default)

    override fun setPowEnabled(value: Boolean) =
        settings.putBoolean(TransportConfigKeys.POW_ENABLED, value)

    override fun getPowDifficulty(default: Int): Int =
        settings.getInt(TransportConfigKeys.POW_DIFFICULTY, default)

    override fun setPowDifficulty(value: Int) =
        settings.putInt(TransportConfigKeys.POW_DIFFICULTY, value)

    override fun getRelayLastUpdateMs(default: Long): Long =
        settings.getLong(TransportConfigKeys.RELAY_LAST_UPDATE_MS, default)

    override fun setRelayLastUpdateMs(value: Long) =
        settings.putLong(TransportConfigKeys.RELAY_LAST_UPDATE_MS, value)

    override fun getAliasRegistryJson(): String? =
        settings.getStringOrNull(TransportConfigKeys.GEOHASH_ALIAS_REGISTRY)

    override fun setAliasRegistryJson(json: String) =
        settings.putString(TransportConfigKeys.GEOHASH_ALIAS_REGISTRY, json)

    override fun clearAliasRegistryJson() =
        settings.remove(TransportConfigKeys.GEOHASH_ALIAS_REGISTRY)

    override fun getConversationRegistryJson(): String? =
        settings.getStringOrNull(TransportConfigKeys.GEOHASH_CONVERSATION_REGISTRY)

    override fun setConversationRegistryJson(json: String) =
        settings.putString(TransportConfigKeys.GEOHASH_CONVERSATION_REGISTRY, json)

    override fun clearConversationRegistryJson() =
        settings.remove(TransportConfigKeys.GEOHASH_CONVERSATION_REGISTRY)
}

@SingleIn(AppScope::class)
@Inject
internal class SettingsDebugConfigStore(
    private val settings: SettingsStore,
) : DebugConfigStore {

    override fun getVerboseLogging(default: Boolean): Boolean =
        settings.getBoolean(TransportConfigKeys.VERBOSE_LOGGING, default)

    override fun setVerboseLogging(value: Boolean) =
        settings.putBoolean(TransportConfigKeys.VERBOSE_LOGGING, value)

    override fun getGattServerEnabled(default: Boolean): Boolean =
        settings.getBoolean(TransportConfigKeys.GATT_SERVER_ENABLED, default)

    override fun setGattServerEnabled(value: Boolean) =
        settings.putBoolean(TransportConfigKeys.GATT_SERVER_ENABLED, value)

    override fun getGattClientEnabled(default: Boolean): Boolean =
        settings.getBoolean(TransportConfigKeys.GATT_CLIENT_ENABLED, default)

    override fun setGattClientEnabled(value: Boolean) =
        settings.putBoolean(TransportConfigKeys.GATT_CLIENT_ENABLED, value)

    override fun getPacketRelayEnabled(default: Boolean): Boolean =
        settings.getBoolean(TransportConfigKeys.PACKET_RELAY_ENABLED, default)

    override fun setPacketRelayEnabled(value: Boolean) =
        settings.putBoolean(TransportConfigKeys.PACKET_RELAY_ENABLED, value)

    override fun getMaxConnectionsOverall(default: Int): Int =
        settings.getInt(TransportConfigKeys.MAX_CONNECTIONS_OVERALL, default)

    override fun setMaxConnectionsOverall(value: Int) =
        settings.putInt(TransportConfigKeys.MAX_CONNECTIONS_OVERALL, value)

    override fun getMaxConnectionsServer(default: Int): Int =
        settings.getInt(TransportConfigKeys.MAX_CONNECTIONS_SERVER, default)

    override fun setMaxConnectionsServer(value: Int) =
        settings.putInt(TransportConfigKeys.MAX_CONNECTIONS_SERVER, value)

    override fun getMaxConnectionsClient(default: Int): Int =
        settings.getInt(TransportConfigKeys.MAX_CONNECTIONS_CLIENT, default)

    override fun setMaxConnectionsClient(value: Int) =
        settings.putInt(TransportConfigKeys.MAX_CONNECTIONS_CLIENT, value)

    override fun getSeenPacketCapacity(default: Int): Int =
        settings.getInt(TransportConfigKeys.SEEN_PACKET_CAPACITY, default)

    override fun setSeenPacketCapacity(value: Int) =
        settings.putInt(TransportConfigKeys.SEEN_PACKET_CAPACITY, value)

    override fun getGcsMaxFilterBytes(default: Int): Int =
        settings.getInt(TransportConfigKeys.GCS_MAX_FILTER_BYTES, default)

    override fun setGcsMaxFilterBytes(value: Int) =
        settings.putInt(TransportConfigKeys.GCS_MAX_FILTER_BYTES, value)

    override fun getGcsFprPercent(default: Double): Double =
        settings.getDouble(TransportConfigKeys.GCS_FILTER_FPR_PERCENT, default)

    override fun setGcsFprPercent(value: Double) =
        settings.putDouble(TransportConfigKeys.GCS_FILTER_FPR_PERCENT, value)
}
