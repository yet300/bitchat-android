package com.bitchat.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.arkivanov.decompose.defaultComponentContext
import com.bitchat.android.feature.root.DeepLinkData
import com.bitchat.android.feature.root.DefaultRootComponent
import com.bitchat.android.feature.root.RootComponent
import com.bitchat.android.onboarding.BatteryOptimizationManager
import com.bitchat.android.onboarding.BluetoothStatusManager
import com.bitchat.android.onboarding.LocationStatusManager
import com.bitchat.android.onboarding.OnboardingCoordinator
import com.bitchat.android.onboarding.PermissionManager
import com.bitchat.android.ui.BitchatNotificationManager
import com.bitchat.android.ui.OrientationAwareActivity
import org.koin.android.ext.android.inject

class MainActivity : OrientationAwareActivity() {

    private val bluetoothStatusManager: BluetoothStatusManager by inject()
    private val locationStatusManager: LocationStatusManager by inject()
    private val batteryOptimizationManager: BatteryOptimizationManager by inject()
    private val permissionManager: PermissionManager by inject()
    
    private lateinit var root: RootComponent

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                batteryOptimizationManager.checkBatteryOptimizationStatus()
            }
        )

        // Create OnboardingCoordinator with Activity context (requires Activity for permission launchers)
        val onboardingCoordinator = OnboardingCoordinator(
            activity = this,
            permissionManager = permissionManager
        )

        // Extract initial deep link from intent
        val initialDeepLink = extractDeepLinkFromIntent(intent)

        root = DefaultRootComponent(
            componentContext = defaultComponentContext(),
            onboardingCoordinator = onboardingCoordinator,
            initialDeepLink = initialDeepLink
        )

        setContent {
            enableEdgeToEdge()
            App(component = root)
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
            BitchatNotificationManager.EXTRA_OPEN_PRIVATE_CHAT,
            false
        )

        val shouldOpenGeohashChat = intent.getBooleanExtra(
            BitchatNotificationManager.EXTRA_OPEN_GEOHASH_CHAT,
            false
        )

        return when {
            shouldOpenPrivateChat -> {
                val peerID = intent.getStringExtra(
                    BitchatNotificationManager.EXTRA_PEER_ID
                ) ?: return null
                val senderNickname = intent.getStringExtra(
                    BitchatNotificationManager.EXTRA_SENDER_NICKNAME
                )
                DeepLinkData.PrivateChat(peerID, senderNickname)
            }

            shouldOpenGeohashChat -> {
                val geohash = intent.getStringExtra(
                    BitchatNotificationManager.EXTRA_GEOHASH
                ) ?: return null
                DeepLinkData.GeohashChat(geohash)
            }

            else -> null
        }
    }
}
