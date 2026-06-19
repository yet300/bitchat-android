@file:OptIn(ExperimentalCoroutinesApi::class)

package com.yet.bitmessage.feature.chats.details.notes.store

import com.app.domain.model.Fingerprint
import com.app.domain.model.LocationNote
import com.app.domain.model.MyIdentity
import com.app.domain.model.PeerId
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.LocationNotesRepository
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocationNotesStoreFactoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeLocationNotes : LocationNotesRepository {
        val selected = mutableListOf<String>()
        val sent = mutableListOf<Pair<String, String?>>()
        val notes = MutableStateFlow<List<LocationNote>>(emptyList())
        val loading = MutableStateFlow(true)
        override fun observeNotes(): Flow<List<LocationNote>> = notes
        override fun observeLoading(): Flow<Boolean> = loading
        override fun select(geohash: String) { selected += geohash }
        override suspend fun send(content: String, nickname: String?) { sent += content to nickname }
    }

    private class FakeIdentity : IdentityRepository {
        override suspend fun myIdentity() = MyIdentity(PeerId("me"), Fingerprint("ff"), "bob")
        override fun observeVerifiedFingerprints(): Flow<Set<Fingerprint>> = MutableStateFlow(emptySet())
        override suspend fun isVerified(fingerprint: Fingerprint) = false
        override suspend fun setVerified(fingerprint: Fingerprint, verified: Boolean) = Unit
        override suspend fun panicWipe() = Unit
    }

    private fun store(repo: FakeLocationNotes, geohash: String = "u4pruyd0") =
        LocationNotesStoreFactory(DefaultStoreFactory(), repo, FakeIdentity(), geohash).create()

    @Test
    fun load_selects_the_geohash_and_streams_notes() = runTest {
        val repo = FakeLocationNotes()
        val store = store(repo)

        assertEquals(listOf("u4pruyd0"), repo.selected)

        repo.notes.value = listOf(LocationNote("n1", "bob#abcd", "hi here", 1700))
        repo.loading.value = false
        assertEquals(listOf("n1"), store.state.notes.map { it.id })
        assertTrue(!store.state.isLoading)
    }

    @Test
    fun send_posts_with_my_nickname_and_clears_the_draft() = runTest {
        val repo = FakeLocationNotes()
        val store = store(repo)

        store.accept(LocationNotesStore.Intent.DraftChanged("first note"))
        store.accept(LocationNotesStore.Intent.SendClicked)

        assertEquals(listOf<Pair<String, String?>>("first note" to "bob"), repo.sent)
        assertEquals("", store.state.draft)
    }
}
