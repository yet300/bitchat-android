package com.app.transport.debug

import android.content.Context
import com.app.common.appSettings
import com.russhwolf.settings.Settings

/**
 * Settings-backed persistence for debug options.
 * Keeps the DebugSettingsManager stateless with regard to Android Context.
 */
object DebugPreferenceManager {
    private const val KEY_VERBOSE = "verbose_logging"
    private const val KEY_GATT_SERVER = "gatt_server_enabled"
    private const val KEY_GATT_CLIENT = "gatt_client_enabled"
    private const val KEY_PACKET_RELAY = "packet_relay_enabled"
    private const val KEY_MAX_CONN_OVERALL = "max_connections_overall"
    private const val KEY_MAX_CONN_SERVER = "max_connections_server"
    private const val KEY_MAX_CONN_CLIENT = "max_connections_client"
    private const val KEY_SEEN_PACKET_CAP = "seen_packet_capacity"
    // GCS keys (no migration/back-compat)
    private const val KEY_GCS_MAX_BYTES = "gcs_max_filter_bytes"
    private const val KEY_GCS_FPR = "gcs_filter_fpr_percent"
    // Removed: persistent notification toggle is now governed by MeshServicePreferences.isBackgroundEnabled

    private lateinit var settings: Settings

    fun init(context: Context) {
        settings = appSettings(context)
    }

    private fun ready(): Boolean = ::settings.isInitialized

    fun getVerboseLogging(default: Boolean = false): Boolean =
        if (ready()) settings.getBoolean(KEY_VERBOSE, default) else default

    fun setVerboseLogging(value: Boolean) {
        if (ready()) settings.putBoolean(KEY_VERBOSE, value)
    }

    fun getGattServerEnabled(default: Boolean = true): Boolean =
        if (ready()) settings.getBoolean(KEY_GATT_SERVER, default) else default

    fun setGattServerEnabled(value: Boolean) {
        if (ready()) settings.putBoolean(KEY_GATT_SERVER, value)
    }

    fun getGattClientEnabled(default: Boolean = true): Boolean =
        if (ready()) settings.getBoolean(KEY_GATT_CLIENT, default) else default

    fun setGattClientEnabled(value: Boolean) {
        if (ready()) settings.putBoolean(KEY_GATT_CLIENT, value)
    }

    fun getPacketRelayEnabled(default: Boolean = true): Boolean =
        if (ready()) settings.getBoolean(KEY_PACKET_RELAY, default) else default

    fun setPacketRelayEnabled(value: Boolean) {
        if (ready()) settings.putBoolean(KEY_PACKET_RELAY, value)
    }

    // Optional connection limits (0 or missing => use defaults)
    fun getMaxConnectionsOverall(default: Int = 8): Int =
        if (ready()) settings.getInt(KEY_MAX_CONN_OVERALL, default) else default

    fun setMaxConnectionsOverall(value: Int) {
        if (ready()) settings.putInt(KEY_MAX_CONN_OVERALL, value)
    }

    fun getMaxConnectionsServer(default: Int = 8): Int =
        if (ready()) settings.getInt(KEY_MAX_CONN_SERVER, default) else default

    fun setMaxConnectionsServer(value: Int) {
        if (ready()) settings.putInt(KEY_MAX_CONN_SERVER, value)
    }

    fun getMaxConnectionsClient(default: Int = 8): Int =
        if (ready()) settings.getInt(KEY_MAX_CONN_CLIENT, default) else default

    fun setMaxConnectionsClient(value: Int) {
        if (ready()) settings.putInt(KEY_MAX_CONN_CLIENT, value)
    }

    // Sync/GCS settings
    fun getSeenPacketCapacity(default: Int = 500): Int =
        if (ready()) settings.getInt(KEY_SEEN_PACKET_CAP, default) else default

    fun setSeenPacketCapacity(value: Int) {
        if (ready()) settings.putInt(KEY_SEEN_PACKET_CAP, value)
    }

    fun getGcsMaxFilterBytes(default: Int = 400): Int =
        if (ready()) settings.getInt(KEY_GCS_MAX_BYTES, default) else default

    fun setGcsMaxFilterBytes(value: Int) {
        if (ready()) settings.putInt(KEY_GCS_MAX_BYTES, value)
    }

    fun getGcsFprPercent(default: Double = 1.0): Double =
        if (ready()) settings.getDouble(KEY_GCS_FPR, default) else default

    fun setGcsFprPercent(value: Double) {
        if (ready()) settings.putDouble(KEY_GCS_FPR, value)
    }

    // No longer storing persistent notification in debug prefs.
}
