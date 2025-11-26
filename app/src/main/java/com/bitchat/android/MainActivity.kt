package com.bitchat.android

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.arkivanov.decompose.defaultComponentContext
import com.arkivanov.decompose.retainedComponent
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.onboarding.BatteryOptimizationManager
import com.bitchat.android.onboarding.BatteryOptimizationStatus
import com.bitchat.android.onboarding.BluetoothStatus
import com.bitchat.android.onboarding.BluetoothStatusManager
import com.bitchat.android.onboarding.LocationStatus
import com.bitchat.android.onboarding.LocationStatusManager
import com.bitchat.android.onboarding.OnboardingCoordinator
import com.bitchat.android.onboarding.OnboardingState
import com.bitchat.android.onboarding.PermissionManager
import com.bitchat.android.feature.root.DefaultRootComponent
import com.bitchat.android.ui.screens.root.RootContent
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.ui.OrientationAwareActivity
import com.bitchat.android.ui.theme.BitchatTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class MainActivity : OrientationAwareActivity() {

    private val permissionManager: PermissionManager by inject()
    private lateinit var onboardingCoordinator: OnboardingCoordinator
    private lateinit var bluetoothStatusManager: BluetoothStatusManager
    private lateinit var locationStatusManager: LocationStatusManager
    private lateinit var batteryOptimizationManager: BatteryOptimizationManager

    // Core mesh service - managed at app level
    private val meshService: BluetoothMeshService by inject()

    private val mainViewModel: MainViewModel by viewModels()
    private val chatViewModel: ChatViewModel by viewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display for modern Android look
        enableEdgeToEdge()

        // Initialize core mesh service first - retrieve from Koin
        bluetoothStatusManager = BluetoothStatusManager(
            activity = this,
            context = this,
            onBluetoothEnabled = {
                Log.d("MainActivity", "Bluetooth enabled by user")
                mainViewModel.updateBluetoothLoading(false)
                mainViewModel.updateBluetoothStatus(BluetoothStatus.ENABLED)
            },
            onBluetoothDisabled = { message ->
                Log.w("MainActivity", "Bluetooth disabled or failed: $message")
                mainViewModel.updateBluetoothLoading(false)
                mainViewModel.updateBluetoothStatus(bluetoothStatusManager.checkBluetoothStatus())
            }
        )
        locationStatusManager = LocationStatusManager(
            activity = this,
            context = this,
            onLocationEnabled = {
                Log.d("MainActivity", "Location services enabled by user")
                mainViewModel.updateLocationLoading(false)
                mainViewModel.updateLocationStatus(LocationStatus.ENABLED)
            },
            onLocationDisabled = { message ->
                Log.w("MainActivity", "Location services disabled or failed: $message")
                mainViewModel.updateLocationLoading(false)
                mainViewModel.updateLocationStatus(locationStatusManager.checkLocationStatus())
            }
        )
        batteryOptimizationManager = BatteryOptimizationManager(
            activity = this,
            context = this,
            onBatteryOptimizationDisabled = {
                Log.d("MainActivity", "Battery optimization disabled by user")
                mainViewModel.updateBatteryOptimizationLoading(false)
                mainViewModel.updateBatteryOptimizationStatus(BatteryOptimizationStatus.DISABLED)
            },
            onBatteryOptimizationFailed = { message ->
                Log.w("MainActivity", "Battery optimization disable failed: $message")
                mainViewModel.updateBatteryOptimizationLoading(false)
                val currentStatus = when {
                    !batteryOptimizationManager.isBatteryOptimizationSupported() -> BatteryOptimizationStatus.NOT_SUPPORTED
                    batteryOptimizationManager.isBatteryOptimizationDisabled() -> BatteryOptimizationStatus.DISABLED
                    else -> BatteryOptimizationStatus.ENABLED
                }
                mainViewModel.updateBatteryOptimizationStatus(currentStatus)
            }
        )
        onboardingCoordinator = OnboardingCoordinator(
            activity = this,
            permissionManager = permissionManager,
            onOnboardingComplete = {
                // Handled by DefaultOnboardingComponent logic observing permissions
                Log.d("MainActivity", "Onboarding coordinator complete callback")
            },
            onOnboardingFailed = { message ->
                Log.e("MainActivity", "Onboarding failed: $message")
                mainViewModel.updateErrorMessage(message)
                mainViewModel.updateOnboardingState(OnboardingState.ERROR)
            }
        )

        val root = DefaultRootComponent(
            componentContext = defaultComponentContext(),
            mainViewModel = mainViewModel,
            bluetoothStatusManager = bluetoothStatusManager,
            locationStatusManager = locationStatusManager,
            batteryOptimizationManager = batteryOptimizationManager,
            onboardingCoordinator = onboardingCoordinator,
            permissionManager = permissionManager,
            onInitializeApp = { initializeApp() }
        )

        setContent {
            BitchatTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    RootContent(
                        component = root,
                        bluetoothStatusManager = bluetoothStatusManager,
                        locationStatusManager = locationStatusManager,
                        batteryOptimizationManager = batteryOptimizationManager,
                        onboardingCoordinator = onboardingCoordinator,
                        permissionManager = permissionManager,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
        
        // Collect state changes in a lifecycle-aware manner
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                mainViewModel.onboardingState.collect { state ->
                    handleOnboardingStateChange(state)
                }
            }
        }
    }
    
    private fun handleOnboardingStateChange(state: OnboardingState) {

        when (state) {
            OnboardingState.COMPLETE -> {
                // App is fully initialized, mesh service is running
                android.util.Log.d("MainActivity", "Onboarding completed - app ready")
            }
            OnboardingState.ERROR -> {
                android.util.Log.e("MainActivity", "Onboarding error state reached")
            }
            else -> {}
        }
    }

    private suspend fun initializeApp() {
        Log.d("MainActivity", "Starting app initialization")

        // Set up mesh service delegate and start services
        meshService.delegate = chatViewModel
        meshService.startServices()

        Log.d("MainActivity", "Mesh service started successfully")

        // Handle any notification intent
        handleNotificationIntent(intent)

        // Small delay to ensure mesh service is fully initialized
        delay(500)
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle notification intents when app is already running
        if (mainViewModel.onboardingState.value == OnboardingState.COMPLETE) {
            handleNotificationIntent(intent)
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Check Bluetooth and Location status on resume and handle accordingly
        if (mainViewModel.onboardingState.value == OnboardingState.COMPLETE) {
            // Set app foreground state
            meshService.connectionManager.setAppBackgroundState(false)
            chatViewModel.setAppBackgroundState(false)

            // Check if Bluetooth was disabled while app was backgrounded
            val currentBluetoothStatus = bluetoothStatusManager.checkBluetoothStatus()
            if (currentBluetoothStatus != BluetoothStatus.ENABLED) {
                Log.w("MainActivity", "Bluetooth disabled while app was backgrounded")
                mainViewModel.updateBluetoothStatus(currentBluetoothStatus)
                mainViewModel.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
                mainViewModel.updateBluetoothLoading(false)
                return
            }
            
            // Check if location services were disabled while app was backgrounded
            val currentLocationStatus = locationStatusManager.checkLocationStatus()
            if (currentLocationStatus != LocationStatus.ENABLED) {
                Log.w("MainActivity", "Location services disabled while app was backgrounded")
                mainViewModel.updateLocationStatus(currentLocationStatus)
                mainViewModel.updateOnboardingState(OnboardingState.LOCATION_CHECK)
                mainViewModel.updateLocationLoading(false)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Only set background state if app is fully initialized
        if (mainViewModel.onboardingState.value == OnboardingState.COMPLETE) {
            // Set app background state
            meshService.connectionManager.setAppBackgroundState(true)
            chatViewModel.setAppBackgroundState(true)
        }
    }

    /**
     * Handle intents from notification clicks - open specific private chat or geohash chat
     */
    private fun handleNotificationIntent(intent: Intent) {
        val shouldOpenPrivateChat = intent.getBooleanExtra(
            com.bitchat.android.ui.NotificationManager.EXTRA_OPEN_PRIVATE_CHAT,
            false
        )

        val shouldOpenGeohashChat = intent.getBooleanExtra(
            com.bitchat.android.ui.NotificationManager.EXTRA_OPEN_GEOHASH_CHAT,
            false
        )

        when {
            shouldOpenPrivateChat -> {
                val peerID =
                    intent.getStringExtra(com.bitchat.android.ui.NotificationManager.EXTRA_PEER_ID)
                val senderNickname =
                    intent.getStringExtra(com.bitchat.android.ui.NotificationManager.EXTRA_SENDER_NICKNAME)

                if (peerID != null) {
                    Log.d(
                        "MainActivity",
                        "Opening private chat with $senderNickname (peerID: $peerID) from notification"
                    )

                    // Open the private chat with this peer
                    chatViewModel.startPrivateChat(peerID)

                    // Clear notifications for this sender since user is now viewing the chat
                    chatViewModel.clearNotificationsForSender(peerID)
                }
            }

            shouldOpenGeohashChat -> {
                val geohash =
                    intent.getStringExtra(com.bitchat.android.ui.NotificationManager.EXTRA_GEOHASH)

                if (geohash != null) {
                    Log.d("MainActivity", "Opening geohash chat #$geohash from notification")

                    // Switch to the geohash channel - create appropriate geohash channel level
                    val level = when (geohash.length) {
                        7 -> com.bitchat.android.geohash.GeohashChannelLevel.BLOCK
                        6 -> com.bitchat.android.geohash.GeohashChannelLevel.NEIGHBORHOOD
                        5 -> com.bitchat.android.geohash.GeohashChannelLevel.CITY
                        4 -> com.bitchat.android.geohash.GeohashChannelLevel.PROVINCE
                        2 -> com.bitchat.android.geohash.GeohashChannelLevel.REGION
                        else -> com.bitchat.android.geohash.GeohashChannelLevel.CITY // Default fallback
                    }
                    val geohashChannel = com.bitchat.android.geohash.GeohashChannel(level, geohash)
                    val channelId = com.bitchat.android.geohash.ChannelID.Location(geohashChannel)
                    chatViewModel.selectLocationChannel(channelId)

                    // Update current geohash state for notifications
                    chatViewModel.setCurrentGeohash(geohash)

                    // Clear notifications for this geohash since user is now viewing it
                    chatViewModel.clearNotificationsForGeohash(geohash)
                }
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        
        // Cleanup location status manager
        try {
            locationStatusManager.cleanup()
            Log.d("MainActivity", "Location status manager cleaned up successfully")
        } catch (e: Exception) {
            Log.w("MainActivity", "Error cleaning up location status manager: ${e.message}")
        }
        
        // Stop mesh services if app was fully initialized
        if (mainViewModel.onboardingState.value == OnboardingState.COMPLETE) {
            try {
                meshService.stopServices()
                Log.d("MainActivity", "Mesh services stopped successfully")
            } catch (e: Exception) {
                Log.w("MainActivity", "Error stopping mesh services in onDestroy: ${e.message}")
            }
        }
    }
}
