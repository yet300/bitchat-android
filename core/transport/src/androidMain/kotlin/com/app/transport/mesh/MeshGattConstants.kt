package com.app.transport.mesh

import java.util.UUID

/**
 * BLE GATT identifiers for the Android mesh bearer. Split out of [com.app.transport.MeshConstants]
 * (now commonMain) because java.util.UUID is JVM/Android-only; iOS uses CoreBluetooth CBUUIDs.
 *
 * iOS byte-compatibility: these UUIDs must stay identical to the iOS service/characteristic.
 */
object MeshGattConstants {
    val SERVICE_UUID: UUID = UUID.fromString("F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")
    val CHARACTERISTIC_UUID: UUID = UUID.fromString("A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")
    val DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
