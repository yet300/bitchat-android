package com.app.transport.mesh

import kotlin.test.Test
import kotlin.test.assertEquals

class CoreBluetoothRestoreIdsTest {

    @Test
    fun identifiersMatchNativeAppForStateRestorationContinuity() {
        assertEquals("chat.bitchat.ble.central", CoreBluetoothRestoreIds.CENTRAL)
        assertEquals("chat.bitchat.ble.peripheral", CoreBluetoothRestoreIds.PERIPHERAL)
    }
}
