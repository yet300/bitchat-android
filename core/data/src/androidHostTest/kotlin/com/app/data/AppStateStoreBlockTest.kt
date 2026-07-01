@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.app.data

import com.app.crypto.EncryptionService
import com.app.domain.model.PeerId
import com.app.transport.SeenMessageStore
import com.app.transport.model.BitchatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.time.Clock

class AppStateStoreBlockTest {

    private companion object {
        const val MY_PEER_ID = "aaaaaaaaaaaaaaaa"
        const val BLOCKED_PEER_ID = "bbbbbbbbbbbbbbbb"
        const val OK_PEER_ID = "cccccccccccccccc"
    }

    private lateinit var store: AppStateStore

    @Before
    fun setUp() {
        val encryption = mock<EncryptionService>()
        whenever(encryption.getIdentityFingerprint()).thenReturn(MY_PEER_ID + "dddd")
        val seen = mock<SeenMessageStore>()
        whenever(seen.hasRead(any())).thenReturn(false)
        // Only BLOCKED_PEER_ID is blocked.
        val contacts = FakeContactRepository(blocked = setOf(PeerId(BLOCKED_PEER_ID)))
        store = AppStateStore(encryption, seen, { contacts })
    }

    private fun message(id: String, sender: String?) = BitchatMessage(
        id = id,
        sender = "nick",
        content = "hello",
        timestamp = Clock.System.now(),
        senderPeerID = sender,
    )

    @Test
    fun private_message_from_blocked_peer_is_dropped() {
        store.addPrivateMessage(BLOCKED_PEER_ID, message("m1", BLOCKED_PEER_ID))
        assertTrue(store.privateMessages.value[BLOCKED_PEER_ID].isNullOrEmpty())
    }

    @Test
    fun public_and_channel_messages_from_blocked_peer_are_dropped() {
        store.addPublicMessage(message("m1", BLOCKED_PEER_ID))
        store.addChannelMessage("#general", message("m2", BLOCKED_PEER_ID))
        assertTrue(store.publicMessages.value.isEmpty())
        assertTrue(store.channelMessages.value["#general"].isNullOrEmpty())
    }

    @Test
    fun messages_from_unblocked_peer_pass_through() {
        store.addPrivateMessage(OK_PEER_ID, message("m1", OK_PEER_ID))
        store.addPublicMessage(message("m2", OK_PEER_ID))
        store.addChannelMessage("#general", message("m3", OK_PEER_ID))
        assertEquals(1, store.privateMessages.value[OK_PEER_ID]?.size)
        assertEquals(1, store.publicMessages.value.size)
        assertEquals(1, store.channelMessages.value["#general"]?.size)
    }

    @Test
    fun my_own_echo_to_a_blocked_peer_is_not_dropped() {
        // Outgoing echoes carry my peer id as the sender; the conversation key is the blocked peer.
        store.addPrivateMessage(BLOCKED_PEER_ID, message("m1", MY_PEER_ID))
        assertEquals(1, store.privateMessages.value[BLOCKED_PEER_ID]?.size)
    }
}
