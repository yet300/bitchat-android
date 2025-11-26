package com.bitchat.android.ui.screens.onboarding

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.bitchat.android.MainViewModel
import com.bitchat.android.feature.onboarding.OnboardingComponent
import com.bitchat.android.onboarding.*
import org.koin.androidx.compose.koinViewModel

@Composable
fun OnboardingFlowScreen(
    component: OnboardingComponent,
    bluetoothStatusManager: BluetoothStatusManager,
    locationStatusManager: LocationStatusManager,
    batteryOptimizationManager: BatteryOptimizationManager,
    onboardingCoordinator: OnboardingCoordinator,
    permissionManager: PermissionManager,
    modifier: Modifier = Modifier
) {
    val mainViewModel: MainViewModel = koinViewModel()
    val context = LocalContext.current
    
    val onboardingState by mainViewModel.onboardingState.collectAsState()
    val bluetoothStatus by mainViewModel.bluetoothStatus.collectAsState()
    val locationStatus by mainViewModel.locationStatus.collectAsState()
    val batteryOptimizationStatus by mainViewModel.batteryOptimizationStatus.collectAsState()
    val errorMessage by mainViewModel.errorMessage.collectAsState()
    val isBluetoothLoading by mainViewModel.isBluetoothLoading.collectAsState()
    val isLocationLoading by mainViewModel.isLocationLoading.collectAsState()
    val isBatteryOptimizationLoading by mainViewModel.isBatteryOptimizationLoading.collectAsState()

    // Trigger initial check
    LaunchedEffect(Unit) {
        if (onboardingState == OnboardingState.CHECKING) {
            component.onRetryInitialization()
        }
    }

    DisposableEffect(context, bluetoothStatusManager) {
        val receiver = bluetoothStatusManager.monitorBluetoothState(
            context = context,
            bluetoothStatusManager = bluetoothStatusManager,
            onBluetoothStateChanged = { status ->
                if (status == BluetoothStatus.ENABLED && onboardingState == OnboardingState.BLUETOOTH_CHECK) {
                    component.onRetryBluetooth()
                }
            }
        )

        onDispose {
            try {
                context.unregisterReceiver(receiver)
                Log.d("BluetoothStatusUI", "BroadcastReceiver unregistered")
            } catch (e: IllegalStateException) {
                Log.w("BluetoothStatusUI", "Receiver was not registered")
            }
        }
    }

    when (onboardingState) {
        OnboardingState.PERMISSION_REQUESTING -> {
            InitializingScreen(modifier)
        }
        
        OnboardingState.BLUETOOTH_CHECK -> {
            BluetoothCheckScreen(
                modifier = modifier,
                status = bluetoothStatus,
                onEnableBluetooth = {
                    component.onEnableBluetooth()
                },
                onRetry = {
                    component.onRetryBluetooth()
                },
                isLoading = isBluetoothLoading
            )
        }
        
        OnboardingState.LOCATION_CHECK -> {
            LocationCheckScreen(
                modifier = modifier,
                status = locationStatus,
                onEnableLocation = {
                    component.onEnableLocation()
                },
                onRetry = {
                    component.onRetryLocation()
                },
                isLoading = isLocationLoading
            )
        }
        
        OnboardingState.BATTERY_OPTIMIZATION_CHECK -> {
            BatteryOptimizationScreen(
                modifier = modifier,
                status = batteryOptimizationStatus,
                onDisableBatteryOptimization = {
                    component.onDisableBatteryOptimization()
                },
                onRetry = {
                    component.onRetryBatteryOptimization()
                },
                onSkip = {
                    component.onSkipBatteryOptimization()
                },
                isLoading = isBatteryOptimizationLoading
            )
        }
        
        OnboardingState.PERMISSION_EXPLANATION -> {
            PermissionExplanationScreen(
                modifier = modifier,
                permissionCategories = permissionManager.getCategorizedPermissions(),
                onContinue = {
                    component.onRequestPermissions()
                }
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
                errorMessage = errorMessage,
                onRetry = {
                    component.onRetryInitialization()
                },
                onOpenSettings = {
                    component.onOpenSettings()
                }
            )
        }
    }
}
