package com.yet.bitmessage.feature.root

import com.app.domain.model.ConversationId
import com.app.domain.model.ThemeMode
import com.app.domain.repository.OnboardingRepository
import com.app.domain.repository.ThemeRepository
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.router.panels.ChildPanels
import com.arkivanov.decompose.router.panels.ChildPanelsMode
import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.yet.bitmessage.feature.chats.main.ChatsComponent
import com.yet.bitmessage.feature.debug.DebugComponent
import com.yet.bitmessage.feature.map.MapComponent
import com.yet.bitmessage.feature.onboarding.OnboardingComponent
import com.yet.bitmessage.feature.onboarding.OnboardingStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultRootComponentTest {

    @BeforeTest fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeChatsComponent(componentContext: ComponentContext) :
        ChatsComponent, ComponentContext by componentContext {
        val opened = mutableListOf<ConversationId>()

        override val panels: Value<ChildPanels<*, ChatsComponent.Main, *, ChatsComponent.Details, Nothing, Nothing>>
            get() = error("not used in root tests")
        override val sheetSlot: Value<ChildSlot<*, ChatsComponent.SheetChild>>
            get() = error("not used in root tests")

        override fun setMode(mode: ChildPanelsMode) = Unit
        override fun openConversation(id: ConversationId) { opened += id }
        override fun onBackClicked() = Unit
        override fun onDismissSheet() = Unit
    }

    private class FakeOnboardingComponent(
        componentContext: ComponentContext,
        val onFinished: () -> Unit,
    ) : OnboardingComponent, ComponentContext by componentContext {
        override val model: Value<OnboardingComponent.Model> =
            MutableValue(OnboardingComponent.Model(step = OnboardingStep.WELCOME, nickname = ""))

        override fun onNicknameChanged(text: String) = Unit
        override fun onPrimaryClicked() = Unit
        override fun onSkipClicked() = Unit
        override fun onBackClicked() = Unit

        /** Simulate completing onboarding. */
        fun finish() = onFinished()
    }

    private class FakeThemeRepository : ThemeRepository {
        override fun observeTheme(): Flow<ThemeMode> = flowOf(ThemeMode.SYSTEM)
        override suspend fun setTheme(mode: ThemeMode) = Unit
    }

    private class FakeOnboardingRepository(private var completed: Boolean) : OnboardingRepository {
        private val flow = MutableStateFlow(completed)
        override fun isCompleted(): Boolean = completed
        override fun observeCompleted(): Flow<Boolean> = flow
        override suspend fun setCompleted() { completed = true; flow.value = true }
    }

    private fun build(
        onboardingCompleted: Boolean,
        onOnboardingCreated: (FakeOnboardingComponent) -> Unit = {},
        onChatsCreated: (FakeChatsComponent) -> Unit = {},
    ) = DefaultRootComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        onboardingFactory = { ctx, onFinished -> FakeOnboardingComponent(ctx, onFinished).also(onOnboardingCreated) },
        chatsFactory = { ctx, _, _ -> FakeChatsComponent(ctx).also(onChatsCreated) },
        mapFactory = { _, _, _, _ -> FakeMapComponent() },
        debugFactory = { _, _ -> FakeDebugComponent() },
        onboardingRepository = FakeOnboardingRepository(onboardingCompleted),
        themeRepository = FakeThemeRepository(),
    )

    private class FakeMapComponent : MapComponent {
        override val model: Value<MapComponent.Model> =
            MutableValue(MapComponent.Model(initialGeohash = null, selectedGeohash = null))
        override fun onMapTapped(latitude: Double, longitude: Double, zoom: Double) = Unit
        override fun onConfirmClicked() = Unit
        override fun onCloseClicked() = Unit
    }

    private class FakeDebugComponent : DebugComponent {
        override val model: Value<DebugComponent.Model> =
            MutableValue(
                DebugComponent.Model(
                    gattServerEnabled = true,
                    gattClientEnabled = true,
                    verboseLogging = false,
                    packetRelayEnabled = true,
                    seenPacketCapacity = 0,
                    status = "",
                    packetLog = emptyList(),
                ),
            )
        override fun onGattServerToggled(enabled: Boolean) = Unit
        override fun onGattClientToggled(enabled: Boolean) = Unit
        override fun onVerboseToggled(enabled: Boolean) = Unit
        override fun onPacketRelayToggled(enabled: Boolean) = Unit
        override fun onSeenCapacityChanged(value: Int) = Unit
        override fun onRefreshStatus() = Unit
        override fun onCloseClicked() = Unit
    }

    @Test
    fun fresh_install_starts_in_onboarding() {
        val component = build(onboardingCompleted = false)
        assertIs<RootComponent.Child.Onboarding>(component.stack.value.active.instance)
    }

    @Test
    fun completed_onboarding_starts_in_chats() {
        var created: ChatsComponent? = null
        val component = build(onboardingCompleted = true, onChatsCreated = { created = it })

        val child = assertIs<RootComponent.Child.Chats>(component.stack.value.active.instance)
        assertSame(created, child.component)
    }

    @Test
    fun finishing_onboarding_replaces_the_stack_with_chats() {
        var onboarding: FakeOnboardingComponent? = null
        val component = build(onboardingCompleted = false, onOnboardingCreated = { onboarding = it })

        onboarding!!.finish()

        assertIs<RootComponent.Child.Chats>(component.stack.value.active.instance)
    }

    @Test
    fun open_conversation_forwards_the_deep_link_to_the_chats_flow() {
        var created: FakeChatsComponent? = null
        val component = build(onboardingCompleted = true, onChatsCreated = { created = it })
        val id: ConversationId = ConversationId.Private(com.app.domain.model.PeerId("a".repeat(64)))

        component.openConversation(id)

        assertEquals(listOf(id), created!!.opened)
    }
}
