@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.app.data.repository

import com.app.crypto.EncryptionService
import com.app.data.AppStateStore
import com.app.data.FakeContactRepository
import com.app.domain.model.ConversationId
import com.app.transport.SeenMessageStore
import com.app.transport.model.BitchatMessage
import com.app.transport.routing.MeshPeerIdSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.time.Clock

class MessageRepositoryImplTest {

    private companion object {
        const val MY_PEER_ID = "aaaaaaaaaaaaaaaa"
        const val OTHER_PEER_ID = "bbbbbbbbbbbbbbbb"
    }

    private lateinit var appStateStore: AppStateStore
    private var peerId: String = MY_PEER_ID
    private lateinit var repository: MessageRepositoryImpl

    @Before
    fun setUp() {
        val encryption = mock<EncryptionService>()
        whenever(encryption.getIdentityFingerprint()).thenReturn("ffffffffffffffff")
        val seen = mock<SeenMessageStore>()
        whenever(seen.hasRead(any())).thenReturn(false)
        appStateStore = AppStateStore(encryption, seen) { FakeContactRepository() }
        repository = MessageRepositoryImpl(appStateStore, MeshPeerIdSource { peerId })
    }

    private fun message(id: String, sender: String) = BitchatMessage(
        id = id,
        sender = "nick",
        content = "hi",
        timestamp = Clock.System.now(),
        senderPeerID = sender,
    )

    @Test
    fun own_public_messages_are_marked_isMine_via_the_mesh_peer_id() = runTest {
        appStateStore.addPublicMessage(message("m1", MY_PEER_ID))
        appStateStore.addPublicMessage(message("m2", OTHER_PEER_ID))

        val byId = repository.observeMessages(ConversationId.PublicMesh).first().associateBy { it.id }
        assertTrue(byId.getValue("m1").isMine)
        assertFalse(byId.getValue("m2").isMine)
    }

    @Test
    fun a_blank_peer_id_marks_nothing_as_mine() = runTest {
        peerId = ""
        appStateStore.addPublicMessage(message("m1", MY_PEER_ID))
        appStateStore.addPublicMessage(message("m2", ""))

        val byId = repository.observeMessages(ConversationId.PublicMesh).first().associateBy { it.id }
        assertFalse(byId.getValue("m1").isMine)
        assertFalse(byId.getValue("m2").isMine)
    }
}
