package com.bitchat.android.ui.screens.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.bitchat.android.feature.onboarding.OnboardingComponent
import com.bitchat.android.onboarding.BatteryOptimizationScreen
import com.bitchat.android.onboarding.BluetoothCheckScreen
import com.bitchat.android.onboarding.InitializationErrorScreen
import com.bitchat.android.onboarding.InitializingScreen
import com.bitchat.android.onboarding.LocationCheckScreen
import com.bitchat.android.onboarding.OnboardingState
import com.bitchat.android.onboarding.PermissionExplanationScreen

@Composable
fun OnboardingFlowScreen(
    component: OnboardingComponent,
    modifier: Modifier = Modifier
) {
    val state by component.model.subscribeAsState()

    when (state.onboardingState) {
        OnboardingState.PERMISSION_REQUESTING -> {
            InitializingScreen(modifier)
        }
        
        OnboardingState.BLUETOOTH_CHECK -> {
            BluetoothCheckScreen(
                modifier = modifier,
                status = state.bluetoothStatus,
                onEnableBluetooth = component::onEnableBluetooth,
                onRetry = component::onRetryBluetooth,
                isLoading = state.isBluetoothLoading
            )
        }
        
        OnboardingState.LOCATION_CHECK -> {
            LocationCheckScreen(
                modifier = modifier,
                status = state.locationStatus,
                onEnableLocation = component::onEnableLocation,
                onRetry = component::onRetryLocation,
                isLoading = state.isLocationLoading
            )
        }
        
        OnboardingState.BATTERY_OPTIMIZATION_CHECK -> {
            BatteryOptimizationScreen(
                modifier = modifier,
                status = state.batteryStatus,
                onDisableBatteryOptimization = component::onDisableBatteryOptimization,
                onRetry = component::onRetryBatteryOptimization,
                onSkip = component::onSkipBatteryOptimization,
                isLoading = state.isBatteryLoading
            )
        }
        
        OnboardingState.PERMISSION_EXPLANATION -> {
            PermissionExplanationScreen(
                modifier = modifier,
                permissionCategories = state.permissionCategories,
                onContinue = component::onRequestPermissions
            )
        }

        OnboardingState.CHECKING, OnboardingState.INITIALIZING -> {
             InitializingScreen(modifier)
        }
        
        OnboardingState.COMPLETE -> {
            InitializingScreen(modifier)
        }
        
        OnboardingState.ERROR -> {
            InitializationErrorScreen(
                modifier = modifier,
                errorMessage = state.errorMessage,
                onRetry = component::onRetryInitialization,
                onOpenSettings = component::onOpenSettings
            )
        }
    }
}
