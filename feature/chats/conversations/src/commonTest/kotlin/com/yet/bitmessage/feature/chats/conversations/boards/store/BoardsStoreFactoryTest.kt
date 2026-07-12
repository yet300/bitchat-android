@file:OptIn(ExperimentalCoroutinesApi::class)

package com.yet.bitmessage.feature.chats.conversations.boards.store

import com.app.domain.model.BoardPost
import com.app.domain.repository.BoardRepository
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun post(id: String, geohash: String, content: String) = BoardPost(
    idHex = id, geohash = geohash, content = content, authorKeyHex = "aa",
    authorNickname = "me", createdAt = 0, expiresAt = 0, isUrgent = false, isMine = true,
)

class BoardsStoreFactoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(testDispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeBoardRepository(
        private val byGeohash: MutableMap<String, List<BoardPost>> = mutableMapOf(),
    ) : BoardRepository {
        override val postArrivals = MutableSharedFlow<BoardPost>(extraBufferCapacity = 8)
        val created = mutableListOf<Triple<String, String, Int>>()
        val deleted = mutableListOf<String>()
        override suspend fun posts(geohash: String): List<BoardPost> = byGeohash[geohash].orEmpty()
        override suspend fun createPost(content: String, geohash: String, urgent: Boolean, expiryDays: Int): Boolean {
            created += Triple(content, geohash, expiryDays)
            byGeohash[geohash] = byGeohash[geohash].orEmpty() + post("new-$content", geohash, content)
            return true
        }
        override suspend fun deletePost(postIdHex: String): Boolean { deleted += postIdHex; return true }
    }

    private fun factory(repo: BoardRepository) = BoardsStoreFactory(DefaultStoreFactory(), repo)

    @Test
    fun loads_the_mesh_local_board_on_subscribe() = runTest {
        val repo = FakeBoardRepository(mutableMapOf("" to listOf(post("p1", "", "hi"))))
        val store = factory(repo).create()
        assertEquals(listOf("p1"), store.state.posts.map { it.idHex })
    }

    @Test
    fun selecting_a_board_switches_and_reloads() = runTest {
        val repo = FakeBoardRepository(mutableMapOf("u4pruy" to listOf(post("g1", "u4pruy", "geo"))))
        val store = factory(repo).create()

        store.accept(BoardsStore.Intent.SelectBoard("u4pruy"))

        assertEquals("u4pruy", store.state.geohash)
        assertEquals(listOf("g1"), store.state.posts.map { it.idHex })
    }

    @Test
    fun create_and_delete_route_to_repository() = runTest {
        val repo = FakeBoardRepository(mutableMapOf("" to emptyList()))
        val store = factory(repo).create()

        store.accept(BoardsStore.Intent.CreatePost("hello", urgent = true, expiryDays = 3))
        store.accept(BoardsStore.Intent.Delete("p9"))

        assertEquals(listOf(Triple("hello", "", 3)), repo.created)
        assertTrue(repo.deleted.contains("p9"))
        assertTrue(store.state.posts.any { it.content == "hello" })
    }
}
