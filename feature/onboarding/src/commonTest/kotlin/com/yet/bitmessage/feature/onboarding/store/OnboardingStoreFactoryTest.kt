@file:OptIn(ExperimentalCoroutinesApi::class)

package com.yet.bitmessage.feature.onboarding.store

import com.app.domain.model.TransportKind
import com.app.domain.model.TransportStatus
import com.app.domain.repository.BatteryOptimizationRepository
import com.app.common.permission.AppPermission
import com.app.common.permission.PermissionController
import com.app.domain.repository.ConnectivityRepository
import com.app.domain.repository.OnboardingRepository
import com.app.domain.repository.SettingsRepository
import com.app.domain.usecase.RequestBatteryExemptionOnceUseCase
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import com.yet.bitmessage.feature.onboarding.OnboardingStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingStoreFactoryTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(testDispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeSettingsRepository(initial: String = "anon1234") : SettingsRepository {
        val nickname = MutableStateFlow(initial)
        override fun observeNickname(): Flow<String> = nickname
        override suspend fun setNickname(value: String) { nickname.value = value }
        override var locationServicesEnabled: Boolean = false
    }

    private class FakeOnboardingRepository : OnboardingRepository {
        var completed = false
        override fun isCompleted(): Boolean = completed
        override fun observeCompleted(): Flow<Boolean> = MutableStateFlow(completed)
        override suspend fun setCompleted() { completed = true }
    }

    private class FakeConnectivityRepository : ConnectivityRepository {
        val enabled = mutableListOf<TransportKind>()
        override fun observe(): Flow<List<TransportStatus>> = MutableStateFlow(emptyList())
        override suspend fun enable(kind: TransportKind) { enabled += kind }
    }

    private class FakePermissionController : PermissionController {
        var requests = 0
        override fun observeGranted(permission: AppPermission): Flow<Boolean> = MutableStateFlow(false)
        override suspend fun requestPermission(permission: AppPermission) { requests++ }
    }

    private class FakeBatteryOptimizationRepository : BatteryOptimizationRepository {
        var asked = false
        var requests = 0
        override fun isExempt(): Boolean = false
        override fun hasAsked(): Boolean = asked
        override fun markAsked() { asked = true }
        override suspend fun requestExemption() { requests++ }
    }

    private fun store(
        settings: SettingsRepository = FakeSettingsRepository(),
        onboarding: OnboardingRepository = FakeOnboardingRepository(),
        connectivity: ConnectivityRepository = FakeConnectivityRepository(),
        notifications: PermissionController = FakePermissionController(),
        battery: BatteryOptimizationRepository = FakeBatteryOptimizationRepository(),
    ) = OnboardingStoreFactory(
        DefaultStoreFactory(),
        settings,
        onboarding,
        connectivity,
        notifications,
        RequestBatteryExemptionOnceUseCase(battery),
    ).create()

    @Test
    fun loads_the_current_nickname() = runTest {
        val store = store(settings = FakeSettingsRepository(initial = "alice"))
        assertEquals("alice", store.state.nickname)
    }

    @Test
    fun primary_advances_and_back_regresses_clamped_at_the_ends() = runTest {
        val store = store()
        assertEquals(OnboardingStep.WELCOME, store.state.step)

        store.accept(OnboardingStore.Intent.Back) // clamps at WELCOME
        assertEquals(OnboardingStep.WELCOME, store.state.step)

        store.accept(OnboardingStore.Intent.Primary)
        assertEquals(OnboardingStep.NICKNAME, store.state.step)
        store.accept(OnboardingStore.Intent.Skip) // Skip advances like Primary
        assertEquals(OnboardingStep.NEARBY, store.state.step)
        store.accept(OnboardingStore.Intent.Back)
        assertEquals(OnboardingStep.NICKNAME, store.state.step)
    }

    @Test
    fun nickname_change_writes_through_to_settings() = runTest {
        val settings = FakeSettingsRepository()
        val store = store(settings = settings)
        store.accept(OnboardingStore.Intent.NicknameChanged("bob"))
        assertEquals("bob", settings.nickname.value)
        assertEquals("bob", store.state.nickname)
    }

    @Test
    fun primer_steps_request_permission_then_advance_and_denial_does_not_block() = runTest {
        val connectivity = FakeConnectivityRepository()
        val notifications = FakePermissionController()
        val battery = FakeBatteryOptimizationRepository()
        val store = store(connectivity = connectivity, notifications = notifications, battery = battery)

        store.accept(OnboardingStore.Intent.Primary) // WELCOME -> NICKNAME (no request)
        store.accept(OnboardingStore.Intent.Primary) // NICKNAME -> NEARBY (no request)
        assertEquals(OnboardingStep.NEARBY, store.state.step)
        assertTrue(connectivity.enabled.isEmpty())
        assertEquals(0, battery.requests)

        store.accept(OnboardingStore.Intent.Primary) // NEARBY: enable bluetooth + battery exemption, then advance
        assertEquals(listOf(TransportKind.BLUETOOTH), connectivity.enabled)
        assertEquals(1, battery.requests)
        assertEquals(OnboardingStep.NOTIFICATIONS, store.state.step)

        store.accept(OnboardingStore.Intent.Primary) // NOTIFICATIONS: request, then advance
        assertEquals(1, notifications.requests)
        assertEquals(OnboardingStep.DONE, store.state.step)
    }

    @Test
    fun skip_on_a_primer_step_advances_without_requesting() = runTest {
        val connectivity = FakeConnectivityRepository()
        val notifications = FakePermissionController()
        val store = store(connectivity = connectivity, notifications = notifications)

        store.accept(OnboardingStore.Intent.Primary) // -> NICKNAME
        store.accept(OnboardingStore.Intent.Primary) // -> NEARBY
        store.accept(OnboardingStore.Intent.Skip)    // NEARBY -> NOTIFICATIONS, no request
        store.accept(OnboardingStore.Intent.Skip)    // NOTIFICATIONS -> DONE, no request

        assertEquals(OnboardingStep.DONE, store.state.step)
        assertTrue(connectivity.enabled.isEmpty())
        assertEquals(0, notifications.requests)
    }

    @Test
    fun finish_persists_completion_and_emits_finished() = runTest {
        val onboarding = FakeOnboardingRepository()
        val store = store(onboarding = onboarding)
        val labels = mutableListOf<OnboardingStore.Label>()
        val job = launch(testDispatcher) { store.labels.toList(labels) }

        assertFalse(onboarding.completed)
        store.accept(OnboardingStore.Intent.Finish)

        assertTrue(onboarding.completed)
        assertEquals(listOf<OnboardingStore.Label>(OnboardingStore.Label.Finished), labels)
        job.cancel()
    }
}
