package com.bitchat.android.feature.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.replaceCurrent
import com.arkivanov.decompose.value.Value
import com.bitchat.android.MainViewModel
import com.bitchat.android.onboarding.BatteryOptimizationManager
import com.bitchat.android.onboarding.BluetoothStatusManager
import com.bitchat.android.feature.onboarding.DefaultOnboardingComponent
import com.bitchat.android.onboarding.LocationStatusManager
import com.bitchat.android.onboarding.OnboardingCoordinator
import com.bitchat.android.onboarding.PermissionManager
import com.bitchat.android.feature.chat.DefaultChatComponent
import kotlinx.serialization.Serializable

class DefaultRootComponent(
    componentContext: ComponentContext,
    private val mainViewModel: MainViewModel,
    private val bluetoothStatusManager: BluetoothStatusManager,
    private val locationStatusManager: LocationStatusManager,
    private val batteryOptimizationManager: BatteryOptimizationManager,
    private val onboardingCoordinator: OnboardingCoordinator,
    private val permissionManager: PermissionManager,
    private val onInitializeApp: suspend () -> Unit,
) : RootComponent, ComponentContext by componentContext {

    private val navigation = StackNavigation<Config>()

    override val childStack: Value<ChildStack<*, RootComponent.Child>> =
        childStack(
            source = navigation,
            serializer = Config.serializer(),
            initialConfiguration = Config.Onboarding,
            handleBackButton = true,
            childFactory = ::createChild,
        )

    private fun createChild(config: Config, componentContext: ComponentContext): RootComponent.Child =
        when (config) {
            Config.Onboarding -> RootComponent.Child.Onboarding(
                DefaultOnboardingComponent(
                    componentContext = componentContext,
                    mainViewModel = mainViewModel,
                    bluetoothStatusManager = bluetoothStatusManager,
                    locationStatusManager = locationStatusManager,
                    batteryOptimizationManager = batteryOptimizationManager,
                    onboardingCoordinator = onboardingCoordinator,
                    permissionManager = permissionManager,
                    onInitializeApp = onInitializeApp,
                    onOnboardingComplete = {
                        navigation.replaceCurrent(Config.Chat)
                    }
                )
            )
            Config.Chat -> RootComponent.Child.Chat(
                DefaultChatComponent(
                    componentContext = componentContext
                )
            )
        }

    @Serializable
    private sealed interface Config {
        @Serializable
        data object Onboarding : Config

        @Serializable
        data object Chat : Config
    }
}
