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
import com.bitchat.android.core.common.coroutineScope
import com.bitchat.android.feature.chat.ChatComponent
import com.bitchat.android.feature.chat.DefaultChatComponent
import com.bitchat.android.feature.onboarding.DefaultOnboardingComponent
import com.bitchat.android.feature.root.integration.stateToModel
import com.bitchat.android.feature.root.store.RootStoreFactory
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.MeshEventBus
import com.bitchat.android.onboarding.BatteryOptimizationManager
import com.bitchat.android.onboarding.BluetoothStatusManager
import com.bitchat.android.onboarding.LocationStatusManager
import com.bitchat.android.onboarding.OnboardingCoordinator
import com.bitchat.android.onboarding.PermissionManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Delegate that forwards mesh events to MeshEventBus for MVI pattern.
 * ChatStore subscribes to MeshEventBus flows and manages all state.
 */
private class MeshEventBusDelegate(
    private val meshEventBus: MeshEventBus
) : com.bitchat.android.mesh.BluetoothMeshDelegate {
    
    override fun didReceiveMessage(message: com.bitchat.android.model.BitchatMessage) {
        meshEventBus.didReceiveMessage(message)
    }
    
    override fun didUpdatePeerList(peers: List<String>) {
        meshEventBus.didUpdatePeerList(peers)
    }
    
    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        meshEventBus.didReceiveChannelLeave(channel, fromPeer)
    }
    
    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
        meshEventBus.didReceiveDeliveryAck(messageID, recipientPeerID)
    }
    
    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
        meshEventBus.didReceiveReadReceipt(messageID, recipientPeerID)
    }
    
    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return meshEventBus.decryptChannelMessage(encryptedContent, channel)
    }
    
    override fun getNickname(): String? {
        return meshEventBus.getNickname()
    }
    
    override fun isFavorite(peerID: String): Boolean {
        return meshEventBus.isFavorite(peerID)
    }
}

class DefaultRootComponent(
    componentContext: ComponentContext,
    private val onboardingCoordinator: OnboardingCoordinator,
    initialDeepLink: DeepLinkData? = null
) : RootComponent, ComponentContext by componentContext, KoinComponent {

    private val storeFactory: StoreFactory by inject()
    private val meshEventBus: MeshEventBus by inject()
    private val bluetoothStatusManager: BluetoothStatusManager by inject()
    private val locationStatusManager: LocationStatusManager by inject()
    private val batteryOptimizationManager: BatteryOptimizationManager by inject()
    private val permissionManager: PermissionManager by inject()
    private val meshService: BluetoothMeshService by inject()

    private val store = instanceKeeper.getStore {
        RootStoreFactory(storeFactory).create()
    }

    override val model: Value<RootComponent.Model> = store.asValue().map(stateToModel)

    private val navigation = StackNavigation<Config>()
    
    private var isAppInitialized = false
    private var pendingDeepLink: DeepLinkData? = initialDeepLink

    override val childStack: Value<ChildStack<*, RootComponent.Child>> =
        childStack(
            source = navigation,
            serializer = Config.serializer(),
            initialConfiguration = Config.Onboarding,
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
        coroutineScope().launch {
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

        // Set up mesh service delegate - MeshEventBus handles all events for MVI pattern
        // ChatStore subscribes to MeshEventBus flows and manages all state
        meshService.delegate = MeshEventBusDelegate(meshEventBus)
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
        // ChatStore handles notification state via SetAppBackgroundState intent
    }

    private fun handleAppPause() {
        Log.d(TAG, "Handling app pause")
        meshService.connectionManager.setAppBackgroundState(true)
        // ChatStore handles notification state via SetAppBackgroundState intent
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
