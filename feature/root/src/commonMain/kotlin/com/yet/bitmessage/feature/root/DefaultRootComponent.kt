package com.yet.bitmessage.feature.root

import com.app.common.decompose.coroutineScope
import com.app.domain.model.ConversationId
import com.app.domain.model.ThemeMode
import com.app.domain.repository.OnboardingRepository
import com.app.domain.repository.ThemeRepository
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.pop
import com.arkivanov.decompose.router.stack.push
import com.arkivanov.decompose.router.stack.replaceAll
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.yet.bitmessage.feature.chats.main.ChatsComponent
import com.yet.bitmessage.feature.debug.DebugComponent
import com.yet.bitmessage.feature.map.MapComponent
import com.yet.bitmessage.feature.onboarding.OnboardingComponent
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

internal class DefaultRootComponent(
    componentContext: ComponentContext,
    private val onboardingFactory: OnboardingComponent.Factory,
    private val chatsFactory: ChatsComponent.Factory,
    private val mapFactory: MapComponent.Factory,
    private val debugFactory: DebugComponent.Factory,
    onboardingRepository: OnboardingRepository,
    themeRepository: ThemeRepository,
) : RootComponent, ComponentContext by componentContext {

    private val navigation = StackNavigation<Config>()

    private val _themeMode = MutableValue(ThemeMode.SYSTEM)
    override val themeMode: Value<ThemeMode> = _themeMode

    override val stack: Value<ChildStack<*, RootComponent.Child>> = childStack(
        source = navigation,
        serializer = Config.serializer(),
        // Synchronous flag read picks the entry child without a splash flash.
        initialConfiguration = if (onboardingRepository.isCompleted()) Config.Chats else Config.Onboarding,
        handleBackButton = true,
        childFactory = ::createChild,
    )

    init {
        coroutineScope().launch {
            themeRepository.observeTheme().collect { _themeMode.value = it }
        }
    }

    private fun createChild(config: Config, componentContext: ComponentContext): RootComponent.Child =
        when (config) {
            is Config.Onboarding -> RootComponent.Child.Onboarding(
                // replaceAll, not push: onboarding must not be back-reachable once completed.
                onboardingFactory.create(componentContext, onFinished = { navigation.replaceAll(Config.Chats) }),
            )
            is Config.Chats -> RootComponent.Child.Chats(
                chatsFactory.create(
                    componentContext,
                    onOpenMap = { initialGeohash -> navigation.push(Config.Map(initialGeohash)) },
                    onOpenDebug = { navigation.push(Config.Debug) },
                ),
            )
            is Config.Map -> RootComponent.Child.Map(
                mapFactory.create(
                    componentContext = componentContext,
                    initialGeohash = config.initialGeohash,
                    onConfirm = { id ->
                        navigation.pop()
                        openConversation(id)
                    },
                    onClose = { navigation.pop() },
                ),
            )
            is Config.Debug -> RootComponent.Child.Debug(
                debugFactory.create(componentContext, onClose = { navigation.pop() }),
            )
        }

    override fun openConversation(id: ConversationId) {
        // Only the Chats flow exists today; forward the deep-link to its component.
        (stack.value.active.instance as? RootComponent.Child.Chats)?.component?.openConversation(id)
    }

    override fun onBackClicked() = navigation.pop()

    @Serializable
    private sealed interface Config {
        @Serializable
        data object Onboarding : Config

        @Serializable
        data object Chats : Config

        @Serializable
        data class Map(val initialGeohash: String?) : Config

        @Serializable
        data object Debug : Config
    }
}

@Inject
internal class DefaultRootComponentFactory(
    private val onboardingFactory: OnboardingComponent.Factory,
    private val chatsFactory: ChatsComponent.Factory,
    private val mapFactory: MapComponent.Factory,
    private val debugFactory: DebugComponent.Factory,
    private val onboardingRepository: OnboardingRepository,
    private val themeRepository: ThemeRepository,
) : RootComponent.Factory {
    override fun create(componentContext: ComponentContext): RootComponent =
        DefaultRootComponent(
            componentContext = componentContext,
            onboardingFactory = onboardingFactory,
            chatsFactory = chatsFactory,
            mapFactory = mapFactory,
            debugFactory = debugFactory,
            onboardingRepository = onboardingRepository,
            themeRepository = themeRepository,
        )
}
