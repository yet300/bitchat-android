package com.bitchat.android.feature.root

import android.util.Log
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.replaceCurrent
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.essenty.lifecycle.doOnCreate
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.arkivanov.essenty.lifecycle.doOnPause
import com.arkivanov.essenty.lifecycle.doOnResume
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.bitchat.android.core.common.asValue
import com.bitchat.android.feature.chat.ChatComponent
import com.bitchat.android.feature.chat.DefaultChatComponent
import com.bitchat.android.feature.onboarding.DefaultOnboardingComponent
import com.bitchat.android.feature.root.integration.stateToModel
import com.bitchat.android.feature.root.store.RootStoreFactory
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.onboarding.BatteryOptimizationManager
import com.bitchat.android.onboarding.BluetoothStatusManager
import com.bitchat.android.onboarding.LocationStatusManager
import com.bitchat.android.onboarding.OnboardingCoordinator
import com.bitchat.android.onboarding.PermissionManager
import com.bitchat.android.ui.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class DefaultRootComponent(
    componentContext: ComponentContext,
    private val bluetoothStatusManager: BluetoothStatusManager,
    private val locationStatusManager: LocationStatusManager,
    private val batteryOptimizationManager: BatteryOptimizationManager,
    private val onboardingCoordinator: OnboardingCoordinator,
    private val permissionManager: PermissionManager,
    private val meshService: BluetoothMeshService,
    private val chatViewModel: ChatViewModel,
    initialDeepLink: DeepLinkData? = null
) : RootComponent, ComponentContext by componentContext, KoinComponent {

    private val storeFactory: StoreFactory by inject()

    private val store = instanceKeeper.getStore {
        RootStoreFactory(storeFactory).create()
    }

    override val model: Value<RootComponent.Model> = store.asValue().map(stateToModel)

    private val navigation = StackNavigation<Config>()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var isAppInitialized = false
    private var pendingDeepLink: DeepLinkData? = initialDeepLink

    override val childStack: Value<ChildStack<*, RootComponent.Child>> =
        childStack(
            source = navigation,
            serializer = Config.serializer(),
            initialConfiguration = Config.Chat(),
            handleBackButton = true,
            childFactory = ::createChild,
        )

    init {
        lifecycle.doOnCreate {
            Log.d(TAG, "RootComponent created")
        }

        lifecycle.doOnResume {
            Log.d(TAG, "RootComponent resumed")
            if (isAppInitialized) {
                handleAppResume()
            }
        }

        lifecycle.doOnPause {
            Log.d(TAG, "RootComponent paused")
            if (isAppInitialized) {
                handleAppPause()
            }
        }

        lifecycle.doOnDestroy {
            Log.d(TAG, "RootComponent destroyed")
            handleCleanup()
        }
    }

    private fun createChild(config: Config, componentContext: ComponentContext): RootComponent.Child =
        when (config) {
            Config.Onboarding -> RootComponent.Child.Onboarding(
                DefaultOnboardingComponent(
                    componentContext = componentContext,
                    bluetoothStatusManager = bluetoothStatusManager,
                    locationStatusManager = locationStatusManager,
                    batteryOptimizationManager = batteryOptimizationManager,
                    onboardingCoordinator = onboardingCoordinator,
                    permissionManager = permissionManager,
                    onOnboardingComplete = {
                        onOnboardingComplete()
                    }
                )
            )
            is Config.Chat -> {
                val startupConfig = when (config.deepLink) {
                    is DeepLinkData.PrivateChat -> ChatComponent.ChatStartupConfig.PrivateChat(config.deepLink.peerID)
                    is DeepLinkData.GeohashChat -> ChatComponent.ChatStartupConfig.GeohashChat(config.deepLink.geohash)
                    null -> ChatComponent.ChatStartupConfig.Default
                }

                RootComponent.Child.Chat(
                    DefaultChatComponent(
                        componentContext = componentContext,
                        startupConfig = startupConfig
                    )
                )
            }
        }

    private fun onOnboardingComplete() {
        scope.launch {
            try {
                initializeApp()
                
                // Navigate to chat with pending deep link if available
                val chatConfig = if (pendingDeepLink != null) {
                    Config.Chat(deepLink = pendingDeepLink)
                } else {
                    Config.Chat()
                }
                
                navigation.replaceCurrent(chatConfig)
                pendingDeepLink = null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize app", e)
            }
        }
    }

    private suspend fun initializeApp() {
        if (isAppInitialized) {
            Log.d(TAG, "App already initialized, skipping")
            return
        }

        Log.d(TAG, "Starting app initialization")

        // Set up mesh service delegate and start services
        meshService.delegate = chatViewModel
        meshService.startServices()

        isAppInitialized = true
        Log.d(TAG, "App initialization complete")

        // Small delay to ensure mesh service is fully initialized
        delay(500)
    }

    override fun onDeepLink(deepLink: DeepLinkData) {
        if (!isAppInitialized) {
            Log.d(TAG, "App not initialized yet, storing deep link for later")
            pendingDeepLink = deepLink
            return
        }

        // Navigate to chat with deep link
        navigation.replaceCurrent(Config.Chat(deepLink = deepLink))
    }



    private fun handleAppResume() {
        Log.d(TAG, "Handling app resume")
        meshService.connectionManager.setAppBackgroundState(false)
        chatViewModel.setAppBackgroundState(false)
    }

    private fun handleAppPause() {
        Log.d(TAG, "Handling app pause")
        meshService.connectionManager.setAppBackgroundState(true)
        chatViewModel.setAppBackgroundState(true)
    }

    private fun handleCleanup() {
        Log.d(TAG, "Cleaning up resources")
        
        // Cleanup managers
        try {
            bluetoothStatusManager.cleanup()
            Log.d(TAG, "Bluetooth status manager cleaned up")
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up bluetooth status manager: ${e.message}")
        }
        
        try {
            locationStatusManager.cleanup()
            Log.d(TAG, "Location status manager cleaned up")
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up location status manager: ${e.message}")
        }
        
        // Stop mesh services if initialized
        if (isAppInitialized) {
            try {
                meshService.stopServices()
                Log.d(TAG, "Mesh services stopped")
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping mesh services: ${e.message}")
            }
        }
        
        // Cancel coroutine scope
        scope.cancel()
    }

    @Serializable
    private sealed interface Config {
        @Serializable
        data object Onboarding : Config

        @Serializable
        data class Chat(val deepLink: DeepLinkData? = null) : Config
    }

    companion object {
        private const val TAG = "RootComponent"
    }
}
