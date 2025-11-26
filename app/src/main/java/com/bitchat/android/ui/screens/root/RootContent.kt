package com.bitchat.android.ui.screens.root

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import com.bitchat.android.feature.root.RootComponent
import com.bitchat.android.onboarding.BatteryOptimizationManager
import com.bitchat.android.onboarding.BluetoothStatusManager
import com.bitchat.android.onboarding.LocationStatusManager
import com.bitchat.android.onboarding.OnboardingCoordinator
import com.bitchat.android.onboarding.PermissionManager
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.ui.screens.chat.ChatScreen
import com.bitchat.android.ui.screens.onboarding.OnboardingFlowScreen
import org.koin.androidx.compose.koinViewModel

@Composable
fun RootContent(
    component: RootComponent,
    bluetoothStatusManager: BluetoothStatusManager,
    locationStatusManager: LocationStatusManager,
    batteryOptimizationManager: BatteryOptimizationManager,
    onboardingCoordinator: OnboardingCoordinator,
    permissionManager: PermissionManager,
    modifier: Modifier = Modifier
) {
    Children(
        stack = component.childStack,
        modifier = modifier.fillMaxSize(),
        animation = stackAnimation(fade())
    ) {
        when (val child = it.instance) {
            is RootComponent.Child.Onboarding -> {
                OnboardingFlowScreen(
                    component = child.component,
                    bluetoothStatusManager = bluetoothStatusManager,
                    locationStatusManager = locationStatusManager,
                    batteryOptimizationManager = batteryOptimizationManager,
                    onboardingCoordinator = onboardingCoordinator,
                    permissionManager = permissionManager
                )
            }
            is RootComponent.Child.Chat -> {
                ChatScreen(
                    component = child.component,
                    viewModel = koinViewModel()
                )
            }
        }
    }
}
