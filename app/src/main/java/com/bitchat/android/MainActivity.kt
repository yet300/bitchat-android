package com.bitchat.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.defaultComponentContext
import com.bitchat.android.feature.root.DefaultRootComponent
import com.bitchat.android.feature.root.DeepLinkData
import com.bitchat.android.feature.root.RootComponent
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.onboarding.BatteryOptimizationManager
import com.bitchat.android.onboarding.BluetoothStatusManager
import com.bitchat.android.onboarding.LocationStatusManager
import com.bitchat.android.onboarding.OnboardingCoordinator
import com.bitchat.android.onboarding.PermissionManager
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.ui.OrientationAwareActivity
import com.bitchat.android.ui.screens.root.RootContent
import com.bitchat.android.ui.theme.BitchatTheme
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

class MainActivity : OrientationAwareActivity() {

    // Inject singletons
    private val permissionManager: PermissionManager by inject()
    private val meshService: BluetoothMeshService by inject()
    private val chatViewModel: ChatViewModel by viewModel()
    private val bluetoothStatusManager: BluetoothStatusManager by inject()
    private val locationStatusManager: LocationStatusManager by inject()
    private val batteryOptimizationManager: BatteryOptimizationManager by inject()
    
    private val onboardingCoordinator: OnboardingCoordinator by inject { parametersOf(this) }
    
    private lateinit var root: RootComponent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display for modern Android look
        enableEdgeToEdge()

        // Set up ActivityResultLaunchers for the managers
        bluetoothStatusManager.setLauncher(
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                // The manager's internal StateFlow will be updated via BroadcastReceiver
            }
        )

        locationStatusManager.setLauncher(
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                // The manager's internal StateFlow will be updated via BroadcastReceiver
            }
        )

        batteryOptimizationManager.setLauncher(
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                // The manager will check status after returning
            }
        )

        // Extract initial deep link from intent
        val initialDeepLink = extractDeepLinkFromIntent(intent)

        root = DefaultRootComponent(
            componentContext = defaultComponentContext(),
            bluetoothStatusManager = bluetoothStatusManager,
            locationStatusManager = locationStatusManager,
            batteryOptimizationManager = batteryOptimizationManager,
            onboardingCoordinator = onboardingCoordinator,
            permissionManager = permissionManager,
            meshService = meshService,
            chatViewModel = chatViewModel,
            initialDeepLink = initialDeepLink
        )

        setContent {
            BitchatTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.background
                ) { innerPadding ->
                    RootContent(
                        component = root,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        // Extract deep link and pass to component
        val deepLink = extractDeepLinkFromIntent(intent)
        if (deepLink != null) {
            root.onDeepLink(deepLink)
        }
    }

    /**
     * Handle intents from notification clicks - open specific private chat or geohash chat
     */
    private fun extractDeepLinkFromIntent(intent: Intent): DeepLinkData? {
        val shouldOpenPrivateChat = intent.getBooleanExtra(
            com.bitchat.android.ui.NotificationManager.EXTRA_OPEN_PRIVATE_CHAT,
            false
        )

        val shouldOpenGeohashChat = intent.getBooleanExtra(
            com.bitchat.android.ui.NotificationManager.EXTRA_OPEN_GEOHASH_CHAT,
            false
        )

        return when {
            shouldOpenPrivateChat -> {
                val peerID = intent.getStringExtra(
                    com.bitchat.android.ui.NotificationManager.EXTRA_PEER_ID
                ) ?: return null
                val senderNickname = intent.getStringExtra(
                    com.bitchat.android.ui.NotificationManager.EXTRA_SENDER_NICKNAME
                )
                DeepLinkData.PrivateChat(peerID, senderNickname)
            }
            shouldOpenGeohashChat -> {
                val geohash = intent.getStringExtra(
                    com.bitchat.android.ui.NotificationManager.EXTRA_GEOHASH
                ) ?: return null
                DeepLinkData.GeohashChat(geohash)
            }
            else -> null
        }
    }
}
