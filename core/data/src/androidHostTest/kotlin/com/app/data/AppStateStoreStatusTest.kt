@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.app.data

import com.app.crypto.EncryptionService
import com.app.transport.SeenMessageStore
import com.app.transport.model.BitchatMessage
import com.app.transport.model.DeliveryStatus
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.time.Clock

class AppStateStoreStatusTest {

    private lateinit var store: AppStateStore

    @Before
    fun setUp() {
        val encryption = mock<EncryptionService>()
        whenever(encryption.getIdentityFingerprint()).thenReturn("ffffffffffffffff")
        val seen = mock<SeenMessageStore>()
        whenever(seen.hasRead(any())).thenReturn(false)
        store = AppStateStore(encryption, seen, { FakeContactRepository() })
    }

    private fun message(id: String) = BitchatMessage(
        id = id,
        sender = "me",
        content = "file",
        timestamp = Clock.System.now(),
        deliveryStatus = DeliveryStatus.PartiallyDelivered(0, 100),
    )

    @Test
    fun updates_a_public_message_status() {
        store.addPublicMessage(message("M1"))

        store.updateMessageStatus("M1", DeliveryStatus.PartiallyDelivered(50, 100))

        val status = store.publicMessages.value.single { it.id == "M1" }.deliveryStatus
        assertEquals(DeliveryStatus.PartiallyDelivered(50, 100), status)
    }

    @Test
    fun updates_a_channel_message_status() {
        store.addChannelMessage("dev", message("M2"))

        store.updateMessageStatus("M2", DeliveryStatus.PartiallyDelivered(100, 100))

        val status = store.channelMessages.value["dev"]!!.single { it.id == "M2" }.deliveryStatus
        assertEquals(DeliveryStatus.PartiallyDelivered(100, 100), status)
    }

    @Test
    fun updates_a_private_message_status() {
        store.addPrivateMessage("peer1", message("M3"))

        store.updateMessageStatus("M3", DeliveryStatus.PartiallyDelivered(25, 100))

        val status = store.privateMessages.value["peer1"]!!.single { it.id == "M3" }.deliveryStatus
        assertEquals(DeliveryStatus.PartiallyDelivered(25, 100), status)
    }

    @Test
    fun never_downgrades_an_already_higher_status() {
        store.addPrivateMessage("peer1", message("M4"))
        store.updateMessageStatus("M4", DeliveryStatus.Read(by = "peer1", at = Clock.System.now()))

        // A late transfer-progress event must not pull Read back to PartiallyDelivered.
        store.updateMessageStatus("M4", DeliveryStatus.PartiallyDelivered(100, 100))

        val status = store.privateMessages.value["peer1"]!!.single { it.id == "M4" }.deliveryStatus
        assertEquals("peer1", (status as DeliveryStatus.Read).by)
    }

    @Test
    fun ack_sink_advances_a_private_message_through_the_full_ladder() {
        store.addPrivateMessage("peer1", message("M5").copy(deliveryStatus = DeliveryStatus.Sending))
        fun status() = store.privateMessages.value["peer1"]!!.single { it.id == "M5" }.deliveryStatus

        store.updateMessageStatus("M5", DeliveryStatus.Sent)
        assertEquals(DeliveryStatus.Sent, status())

        store.onDeliveryAck("M5", "peer1")
        assertEquals("peer1", (status() as DeliveryStatus.Delivered).to)

        store.onReadReceipt("M5", "peer1")
        assertEquals("peer1", (status() as DeliveryStatus.Read).by)
    }

    @Test
    fun read_receipt_does_not_downgrade_to_delivered() {
        store.addPrivateMessage("peer1", message("M6").copy(deliveryStatus = DeliveryStatus.Sending))
        store.onReadReceipt("M6", "peer1")

        // A late delivery ACK must not pull Read back to Delivered.
        store.onDeliveryAck("M6", "peer1")

        val status = store.privateMessages.value["peer1"]!!.single { it.id == "M6" }.deliveryStatus
        assertEquals("peer1", (status as DeliveryStatus.Read).by)
    }
}
