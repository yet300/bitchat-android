@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.app.data.repository

import com.app.crypto.EncryptionService
import com.app.data.AppStateStore
import com.app.domain.model.ConversationId
import com.app.domain.model.GeohashLevel
import com.app.transport.SeenMessageStore
import com.app.transport.mesh.BluetoothMeshService
import com.app.transport.model.BitchatMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.time.Clock

class ConversationRepositoryGeohashTest {

    private companion object {
        const val MY_PEER_ID = "aaaaaaaaaaaaaaaa"
        const val OTHER_PEER_ID = "bbbbbbbbbbbbbbbb"
        const val GEOHASH = "u4pru"
        const val GEO_TAG = "geo:$GEOHASH"
    }

    private lateinit var appStateStore: AppStateStore
    private lateinit var seenMessageStore: SeenMessageStore
    private lateinit var repository: ConversationRepositoryImpl

    @Before
    fun setUp() {
        val encryptionService = mock<EncryptionService>()
        whenever(encryptionService.getIdentityFingerprint()).thenReturn(MY_PEER_ID + "cccc")
        seenMessageStore = mock()
        whenever(seenMessageStore.hasRead(any())).thenReturn(false)
        appStateStore = AppStateStore(encryptionService, seenMessageStore)

        val mesh = mock<BluetoothMeshService>()
        whenever(mesh.myPeerID).thenReturn(MY_PEER_ID)
        repository = ConversationRepositoryImpl(mesh, appStateStore, seenMessageStore)
    }

    private fun message(id: String) = BitchatMessage(
        id = id,
        sender = "nick",
        content = "hello",
        timestamp = Clock.System.now(),
        senderPeerID = OTHER_PEER_ID,
        channel = GEO_TAG,
    )

    @Test
    fun geoChannelTagSurfacesAsGeohashConversation() = runTest {
        appStateStore.addChannelMessage(GEO_TAG, message("m1"))

        val conversations = repository.observeConversations().first()

        assertEquals(1, conversations.size)
        val conversation = conversations.single()
        val id = conversation.id
        assertTrue("expected Geohash id, got $id", id is ConversationId.Geohash)
        id as ConversationId.Geohash
        assertEquals(GEOHASH, id.channel.geohash)
        assertEquals(GeohashLevel.CITY, id.channel.level)
        assertEquals("#$GEOHASH", conversation.title)
        assertEquals(1, conversation.unreadCount)
    }

    @Test
    fun plainChannelTagStaysChannelConversation() = runTest {
        appStateStore.addChannelMessage("#general", message("m1"))

        val id = repository.observeConversations().first().single().id

        assertEquals(ConversationId.Channel("#general"), id)
    }

    @Test
    fun markReadResetsGeohashUnread() = runTest {
        appStateStore.addChannelMessage(GEO_TAG, message("m1"))
        val id = repository.observeConversations().first().single().id

        repository.markRead(id)

        assertEquals(0, repository.observeUnreadCount().first())
        assertEquals(0, repository.observeConversations().first().single().unreadCount)
    }
}
