package com.yet.bitmessage.feature.chats.conversations.connectivity.store

import com.app.domain.model.TransportKind
import com.app.domain.model.TransportState
import com.app.domain.model.TransportStatus
import com.app.domain.repository.ConnectivityRepository
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectivityStoreFactoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(testDispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class FakeConnectivityRepository(
        private val statuses: List<TransportStatus>,
    ) : ConnectivityRepository {
        val enabled = mutableListOf<TransportKind>()
        override fun observe(): Flow<List<TransportStatus>> = flowOf(statuses)
        override suspend fun enable(kind: TransportKind) { enabled += kind }
    }

    @Test
    fun loads_statuses_and_routes_enable_to_repository() = runTest {
        val repo = FakeConnectivityRepository(
            listOf(
                TransportStatus(TransportKind.BLUETOOTH, TransportState.OFF),
                TransportStatus(TransportKind.INTERNET, TransportState.ON),
            ),
        )
        val store = ConnectivityStoreFactory(DefaultStoreFactory(), repo).create()

        assertEquals(2, store.state.statuses.size)
        assertEquals(TransportState.OFF, store.state.statuses.first().state)

        store.accept(ConnectivityStore.Intent.Enable(TransportKind.BLUETOOTH))
        assertEquals(listOf(TransportKind.BLUETOOTH), repo.enabled)
    }
}
