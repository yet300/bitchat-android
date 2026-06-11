@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.app.data

import com.app.crypto.EncryptionService
import com.app.transport.SeenMessageStore
import com.app.transport.model.BitchatMessage
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.time.Clock

class AppStateStoreUnreadTest {

    private companion object {
        // First 16 hex chars of the fake fingerprint below
        const val MY_PEER_ID = "aaaaaaaaaaaaaaaa"
        const val OTHER_PEER_ID = "bbbbbbbbbbbbbbbb"
    }

    private lateinit var encryptionService: EncryptionService
    private lateinit var seenMessageStore: SeenMessageStore
    private lateinit var store: AppStateStore

    @Before
    fun setUp() {
        encryptionService = mock()
        whenever(encryptionService.getIdentityFingerprint()).thenReturn(MY_PEER_ID + "cccc")
        seenMessageStore = mock()
        whenever(seenMessageStore.hasRead(any())).thenReturn(false)
        store = AppStateStore(encryptionService, seenMessageStore)
    }

    private fun message(id: String, sender: String?) = BitchatMessage(
        id = id,
        sender = "nick",
        content = "hello",
        timestamp = Clock.System.now(),
        senderPeerID = sender,
    )

    @Test
    fun incomingMessagesIncrementPerConversationCounters() {
        store.addPrivateMessage(OTHER_PEER_ID, message("m1", OTHER_PEER_ID))
        store.addPrivateMessage(OTHER_PEER_ID, message("m2", OTHER_PEER_ID))
        store.addChannelMessage("#general", message("m3", OTHER_PEER_ID))
        store.addPublicMessage(message("m4", OTHER_PEER_ID))

        val counts = store.unreadCounts.value
        assertEquals(2, counts[AppStateStore.privateConversationKey(OTHER_PEER_ID)])
        assertEquals(1, counts[AppStateStore.channelConversationKey("#general")])
        assertEquals(1, counts[AppStateStore.publicConversationKey()])
    }

    @Test
    fun ownMessagesDoNotCount() {
        store.addPrivateMessage(OTHER_PEER_ID, message("m1", MY_PEER_ID))
        store.addPublicMessage(message("m2", MY_PEER_ID))

        assertEquals(emptyMap<String, Int>(), store.unreadCounts.value)
    }

    @Test
    fun duplicateMessageCountsOnce() {
        val msg = message("m1", OTHER_PEER_ID)
        store.addPrivateMessage(OTHER_PEER_ID, msg)
        store.addPrivateMessage(OTHER_PEER_ID, msg)

        assertEquals(1, store.unreadCounts.value[AppStateStore.privateConversationKey(OTHER_PEER_ID)])
    }

    @Test
    fun persistedReadMessagesDoNotCount() {
        whenever(seenMessageStore.hasRead("m1")).thenReturn(true)

        store.addPrivateMessage(OTHER_PEER_ID, message("m1", OTHER_PEER_ID))

        assertEquals(emptyMap<String, Int>(), store.unreadCounts.value)
    }

    @Test
    fun markReadResetsCounter() {
        val key = AppStateStore.privateConversationKey(OTHER_PEER_ID)
        store.addPrivateMessage(OTHER_PEER_ID, message("m1", OTHER_PEER_ID))
        assertEquals(1, store.unreadCounts.value[key])

        store.markRead(key)

        assertEquals(emptyMap<String, Int>(), store.unreadCounts.value)

        // New traffic counts again
        store.addPrivateMessage(OTHER_PEER_ID, message("m2", OTHER_PEER_ID))
        assertEquals(1, store.unreadCounts.value[key])
    }

    /** Upstream #707: request-sync replays carry different android-side ids but identical content. */
    @Test
    fun publicTimelineCollapsesRequestSyncReplayWithDifferentIds() {
        val original = message("random-id-from-first-delivery", OTHER_PEER_ID)
        val replay = original.copy(id = "different-random-id-from-replay")

        store.addPublicMessage(original)
        store.addPublicMessage(replay)

        assertEquals(listOf(original), store.publicMessages.value)
        assertEquals(1, store.unreadCounts.value[AppStateStore.publicConversationKey()])
    }

    @Test
    fun clearWipesCounters() {
        store.addPublicMessage(message("m1", OTHER_PEER_ID))

        store.clear()

        assertEquals(emptyMap<String, Int>(), store.unreadCounts.value)
    }
}
