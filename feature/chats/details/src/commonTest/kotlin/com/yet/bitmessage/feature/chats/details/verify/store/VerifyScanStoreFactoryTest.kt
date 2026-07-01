@file:OptIn(ExperimentalCoroutinesApi::class)

package com.yet.bitmessage.feature.chats.details.verify.store

import com.app.common.permission.AppPermission
import com.app.common.permission.PermissionController
import com.app.domain.repository.PeerVerificationRepository
import com.app.domain.repository.VerifyScanResult
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

class VerifyScanStoreFactoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(testDispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakePermissionController : PermissionController {
        val granted = MutableStateFlow(false)
        var requested = false
        override fun observeGranted(permission: AppPermission): Flow<Boolean> = granted
        override suspend fun requestPermission(permission: AppPermission) { requested = true }
    }

    private class FakePeerVerificationRepository(
        private val result: VerifyScanResult = VerifyScanResult.Started("bob"),
    ) : PeerVerificationRepository {
        var calls = 0
        override suspend fun verifyScannedQr(payload: String): VerifyScanResult {
            calls++
            return result
        }
    }

    private fun factory(
        camera: FakePermissionController = FakePermissionController(),
        verify: FakePeerVerificationRepository = FakePeerVerificationRepository(),
    ) = VerifyScanStoreFactory(DefaultStoreFactory(), camera, verify)

    @Test
    fun camera_permission_loads_into_state() = runTest {
        val camera = FakePermissionController().apply { granted.value = true }
        val store = factory(camera = camera).create()
        assertTrue(store.state.cameraGranted)
    }

    @Test
    fun request_camera_permission_routes_to_repository() = runTest {
        val camera = FakePermissionController()
        val store = factory(camera = camera).create()
        store.accept(VerifyScanStore.Intent.RequestCameraPermission)
        assertTrue(camera.requested)
    }

    @Test
    fun scan_routes_payload_and_stores_result() = runTest {
        val verify = FakePeerVerificationRepository(VerifyScanResult.Started("bob"))
        val store = factory(verify = verify).create()

        store.accept(VerifyScanStore.Intent.QrScanned("bitchat://verify?x=1"))

        assertEquals(VerifyScanResult.Started("bob"), store.state.result)
        assertEquals(1, verify.calls)
    }

    @Test
    fun repeat_scan_ignored_once_started() = runTest {
        val verify = FakePeerVerificationRepository(VerifyScanResult.Started("bob"))
        val store = factory(verify = verify).create()

        store.accept(VerifyScanStore.Intent.QrScanned("p1"))
        store.accept(VerifyScanStore.Intent.QrScanned("p2"))

        assertEquals(1, verify.calls)
    }
}
