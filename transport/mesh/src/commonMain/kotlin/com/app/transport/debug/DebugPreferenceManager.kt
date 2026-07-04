package com.app.transport.debug

import com.app.common.settings.SettingsStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Settings-backed persistence for debug options, over the domain [SettingsStore] port.
 * Keeps the [DebugSettingsManager] stateless with regard to Android Context.
 */
@SingleIn(AppScope::class)
@Inject
class DebugPreferenceManager(
    private val settings: SettingsStore,
) {

    fun getVerboseLogging(default: Boolean = false): Boolean = settings.getBoolean(KEY_VERBOSE, default)

    fun setVerboseLogging(value: Boolean) = settings.putBoolean(KEY_VERBOSE, value)

    fun getGattServerEnabled(default: Boolean = true): Boolean = settings.getBoolean(KEY_GATT_SERVER, default)

    fun setGattServerEnabled(value: Boolean) = settings.putBoolean(KEY_GATT_SERVER, value)

    fun getGattClientEnabled(default: Boolean = true): Boolean = settings.getBoolean(KEY_GATT_CLIENT, default)

    fun setGattClientEnabled(value: Boolean) = settings.putBoolean(KEY_GATT_CLIENT, value)

    fun getPacketRelayEnabled(default: Boolean = true): Boolean = settings.getBoolean(KEY_PACKET_RELAY, default)

    fun setPacketRelayEnabled(value: Boolean) = settings.putBoolean(KEY_PACKET_RELAY, value)

    // Optional connection limits (0 or missing => use defaults)
    fun getMaxConnectionsOverall(default: Int = 8): Int = settings.getInt(KEY_MAX_CONN_OVERALL, default)

    fun setMaxConnectionsOverall(value: Int) = settings.putInt(KEY_MAX_CONN_OVERALL, value)

    fun getMaxConnectionsServer(default: Int = 8): Int = settings.getInt(KEY_MAX_CONN_SERVER, default)

    fun setMaxConnectionsServer(value: Int) = settings.putInt(KEY_MAX_CONN_SERVER, value)

    fun getMaxConnectionsClient(default: Int = 8): Int = settings.getInt(KEY_MAX_CONN_CLIENT, default)

    fun setMaxConnectionsClient(value: Int) = settings.putInt(KEY_MAX_CONN_CLIENT, value)

    // Sync/GCS settings
    fun getSeenPacketCapacity(default: Int = 500): Int = settings.getInt(KEY_SEEN_PACKET_CAP, default)

    fun setSeenPacketCapacity(value: Int) = settings.putInt(KEY_SEEN_PACKET_CAP, value)

    fun getGcsMaxFilterBytes(default: Int = 400): Int = settings.getInt(KEY_GCS_MAX_BYTES, default)

    fun setGcsMaxFilterBytes(value: Int) = settings.putInt(KEY_GCS_MAX_BYTES, value)

    fun getGcsFprPercent(default: Double = 1.0): Double = settings.getDouble(KEY_GCS_FPR, default)

    fun setGcsFprPercent(value: Double) = settings.putDouble(KEY_GCS_FPR, value)

    private companion object {
        const val KEY_VERBOSE = "verbose_logging"
        const val KEY_GATT_SERVER = "gatt_server_enabled"
        const val KEY_GATT_CLIENT = "gatt_client_enabled"
        const val KEY_PACKET_RELAY = "packet_relay_enabled"
        const val KEY_MAX_CONN_OVERALL = "max_connections_overall"
        const val KEY_MAX_CONN_SERVER = "max_connections_server"
        const val KEY_MAX_CONN_CLIENT = "max_connections_client"
        const val KEY_SEEN_PACKET_CAP = "seen_packet_capacity"
        // GCS keys (no migration/back-compat)
        const val KEY_GCS_MAX_BYTES = "gcs_max_filter_bytes"
        const val KEY_GCS_FPR = "gcs_filter_fpr_percent"
    }
}
