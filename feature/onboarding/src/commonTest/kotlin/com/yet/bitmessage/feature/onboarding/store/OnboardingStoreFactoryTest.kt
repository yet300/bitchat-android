@file:OptIn(ExperimentalCoroutinesApi::class)

package com.yet.bitmessage.feature.onboarding.store

import com.app.domain.repository.OnboardingRepository
import com.app.domain.repository.SettingsRepository
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

    private fun store(
        settings: SettingsRepository = FakeSettingsRepository(),
        onboarding: OnboardingRepository = FakeOnboardingRepository(),
    ) = OnboardingStoreFactory(DefaultStoreFactory(), settings, onboarding).create()

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
