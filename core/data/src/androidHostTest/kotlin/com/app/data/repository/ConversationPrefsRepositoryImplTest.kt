package com.app.data.repository

import com.app.domain.model.ConversationId
import com.app.domain.model.GeohashChannel
import com.app.domain.model.GeohashLevel
import com.app.domain.model.PeerId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationPrefsRepositoryImplTest {

    private fun repo() = ConversationPrefsRepositoryImpl(InMemoryDatabase().conversationPrefDao)

    @Test
    fun pin_round_trips_across_all_conversation_kinds() = runTest {
        val repo = repo()
        val ids = listOf(
            ConversationId.PublicMesh,
            ConversationId.Channel("dev"),
            ConversationId.Private(PeerId("nostr_0123456789abcdef")),
            ConversationId.Geohash(GeohashChannel(GeohashLevel.CITY, "u4pruyd")),
        )
        ids.forEach { repo.setPinned(it, true) }

        assertEquals(ids.toSet(), repo.observePinned().first())
        assertTrue(repo.observeMuted().first().isEmpty())
    }

    @Test
    fun unpin_removes_only_the_target() = runTest {
        val repo = repo()
        val a = ConversationId.Channel("a")
        val b = ConversationId.Channel("b")
        repo.setPinned(a, true)
        repo.setPinned(b, true)

        repo.setPinned(a, false)

        assertEquals(setOf(b), repo.observePinned().first())
    }

    @Test
    fun pinned_and_muted_are_independent_sets() = runTest {
        val repo = repo()
        val id = ConversationId.Channel("dev")
        repo.setPinned(id, true)
        repo.setMuted(id, true)
        repo.setPinned(id, false)

        assertTrue(repo.observePinned().first().isEmpty())
        assertEquals(setOf(id), repo.observeMuted().first())
    }
}
