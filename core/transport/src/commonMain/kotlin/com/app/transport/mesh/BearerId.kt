package com.app.transport.mesh

import kotlin.jvm.JvmInline

/**
 * Stable identity token for a [MeshBearer] instance.
 *
 * String-backed so new bearer types (BLE, Wi-Fi Aware, TCP…) can define their
 * own well-known ids without adding variants to a sealed class.
 */
@JvmInline
value class BearerId(val id: String) {
    companion object {
        val BLE = BearerId("ble")
        val WIFI_AWARE = BearerId("wifi-aware")
    }
}
