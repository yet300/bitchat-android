@file:OptIn(ExperimentalCoroutinesApi::class)

package com.yet.bitmessage.feature.debug.store

import com.app.domain.model.PacketLogEntry
import com.app.domain.model.PacketLogKind
import com.app.domain.repository.DebugRepository
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DebugStoreFactoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(testDispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeDebugRepository : DebugRepository {
        var gattServer = true
        var gattClient = true
        var verbose = false
        var packetRelay = true
        var seenCap = 500
        var statusReads = 0
        override fun gattServerEnabled() = gattServer
        override fun setGattServerEnabled(enabled: Boolean) { gattServer = enabled }
        override fun gattClientEnabled() = gattClient
        override fun setGattClientEnabled(enabled: Boolean) { gattClient = enabled }
        override fun verboseLoggingEnabled() = verbose
        override fun setVerboseLoggingEnabled(enabled: Boolean) { verbose = enabled }
        override fun packetRelayEnabled() = packetRelay
        override fun setPacketRelayEnabled(enabled: Boolean) { packetRelay = enabled }
        override fun seenPacketCapacity() = seenCap
        override fun setSeenPacketCapacity(value: Int) { seenCap = value }
        override fun debugStatus(): String { statusReads++; return "status-$statusReads" }
        val packetLog = MutableStateFlow<List<PacketLogEntry>>(emptyList())
        override fun observePacketLog(): Flow<List<PacketLogEntry>> = packetLog
    }

    @Test
    fun load_reflects_the_repository_snapshot() = runTest {
        val repo = FakeDebugRepository().apply { gattServer = false; seenCap = 750 }
        val store = DebugStoreFactory(DefaultStoreFactory(), repo).create()

        assertFalse(store.state.gattServerEnabled)
        assertEquals(750, store.state.seenPacketCapacity)
        assertEquals("status-1", store.state.status)
    }

    @Test
    fun toggling_gatt_server_persists_and_updates_state() = runTest {
        val repo = FakeDebugRepository()
        val store = DebugStoreFactory(DefaultStoreFactory(), repo).create()

        store.accept(DebugStore.Intent.SetGattServer(false))

        assertFalse(repo.gattServer)
        assertFalse(store.state.gattServerEnabled)
    }

    @Test
    fun refresh_status_re_reads_the_repository() = runTest {
        val repo = FakeDebugRepository()
        val store = DebugStoreFactory(DefaultStoreFactory(), repo).create() // status-1 on load

        store.accept(DebugStore.Intent.RefreshStatus)

        assertEquals("status-2", store.state.status)
    }

    @Test
    fun packet_log_updates_flow_into_state() = runTest {
        val repo = FakeDebugRepository()
        val store = DebugStoreFactory(DefaultStoreFactory(), repo).create()

        repo.packetLog.value = listOf(PacketLogEntry(PacketLogKind.PACKET, "hello", 1L))

        assertEquals(listOf("hello"), store.state.packetLog.map { it.text })
    }
}
