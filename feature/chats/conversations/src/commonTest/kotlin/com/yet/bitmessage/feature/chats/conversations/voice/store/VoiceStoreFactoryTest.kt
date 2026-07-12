@file:OptIn(ExperimentalCoroutinesApi::class)

package com.yet.bitmessage.feature.chats.conversations.voice.store

import com.app.domain.model.VoiceBurst
import com.app.domain.repository.VoiceRepository
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceStoreFactoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(testDispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeVoiceRepository : VoiceRepository {
        val incoming = MutableSharedFlow<VoiceBurst>(extraBufferCapacity = 8)
        val broadcasts = mutableListOf<Pair<List<ByteArray>, Int>>()
        override val incomingBursts: Flow<VoiceBurst> = incoming
        override suspend fun broadcast(frames: List<ByteArray>, durationMs: Int) {
            broadcasts += frames to durationMs
        }
    }

    private fun factory(repo: VoiceRepository) = VoiceStoreFactory(DefaultStoreFactory(), repo)

    @Test
    fun send_routes_to_repository() = runTest {
        val repo = FakeVoiceRepository()
        val store = factory(repo).create()
        val frames = listOf("a".encodeToByteArray())

        store.accept(VoiceStore.Intent.Send(frames, durationMs = 800))

        assertEquals(1, repo.broadcasts.size)
        assertEquals(800, repo.broadcasts[0].second)
    }

    @Test
    fun incoming_burst_appends_to_log_and_publishes_play_label() = runTest {
        val repo = FakeVoiceRepository()
        val store = factory(repo).create()
        val labels = mutableListOf<VoiceStore.Label>()
        val job = launch(testDispatcher) { store.labels.toList(labels) }

        val frames = listOf("aac".encodeToByteArray())
        repo.incoming.emit(VoiceBurst(peerId = "peerX", frames = frames, durationMs = 1200))

        assertEquals(listOf(VoiceStore.ReceivedBurst("peerX", 1200)), store.state.received)
        assertEquals(1, labels.size)
        assertEquals("aac", (labels[0] as VoiceStore.Label.Play).frames.single().decodeToString())
        job.cancel()
    }
}
