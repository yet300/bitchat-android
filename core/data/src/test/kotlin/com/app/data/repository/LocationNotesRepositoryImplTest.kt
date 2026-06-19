package com.app.data.repository

import com.app.transport.nostr.LocationNotesManager
import com.app.transport.nostr.NostrIdentityBridge
import com.app.transport.nostr.NostrRelayManager
import com.app.transport.nostr.RelayDirectory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class LocationNotesRepositoryImplTest {

    private val relayDirectory = mock<RelayDirectory>()

    private fun repo(manager: LocationNotesManager) =
        LocationNotesRepositoryImpl(manager, mock<NostrRelayManager>(), relayDirectory, mock<NostrIdentityBridge>())

    @Test
    fun select_delegates_to_the_manager() {
        val manager = mock<LocationNotesManager>()
        repo(manager).select("u4pruyd0")
        verify(manager).setGeohash("u4pruyd0")
    }

    @Test
    fun send_delegates_to_the_manager_with_the_relay_directory() = runTest {
        val manager = mock<LocationNotesManager>()
        repo(manager).send("hello here", "bob")
        verify(manager).send(eq("hello here"), eq("bob"), any())
    }

    @Test
    fun notes_map_to_the_domain_with_display_name() = runTest {
        val manager = mock<LocationNotesManager>()
        val note = LocationNotesManager.Note(
            id = "n1",
            pubkey = "abcdef0123456789",
            content = "first post",
            createdAt = 1700,
            nickname = "bob",
        )
        whenever(manager.notes).thenReturn(MutableStateFlow(listOf(note)))

        val mapped = repo(manager).observeNotes().first().single()
        assertEquals("n1", mapped.id)
        assertEquals("first post", mapped.content)
        assertEquals(1700, mapped.createdAtEpochSeconds)
        assertEquals("bob#6789", mapped.authorName)
    }
}
