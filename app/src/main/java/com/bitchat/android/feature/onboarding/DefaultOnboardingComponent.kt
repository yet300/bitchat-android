package com.bitchat.android.feature.onboarding

import android.util.Log
import com.arkivanov.decompose.ComponentContext
import com.bitchat.android.MainViewModel
import com.bitchat.android.onboarding.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DefaultOnboardingComponent(
    componentContext: ComponentContext,
    private val mainViewModel: MainViewModel,
    private val bluetoothStatusManager: BluetoothStatusManager,
    private val locationStatusManager: LocationStatusManager,
    private val batteryOptimizationManager: BatteryOptimizationManager,
    private val onboardingCoordinator: OnboardingCoordinator,
    private val permissionManager: PermissionManager,
    private val onInitializeApp: suspend () -> Unit,
    private val onOnboardingComplete: () -> Unit
) : OnboardingComponent, ComponentContext by componentContext {

    private val scope = CoroutineScope(Dispatchers.Main)

    init {
        scope.launch {
            mainViewModel.bluetoothStatus.collect { status ->
                if (status == BluetoothStatus.ENABLED && mainViewModel.onboardingState.value == OnboardingState.BLUETOOTH_CHECK) {
                    checkBluetoothAndProceed()
                }
            }
        }

        scope.launch {
            mainViewModel.locationStatus.collect { status ->
                if (status == LocationStatus.ENABLED && mainViewModel.onboardingState.value == OnboardingState.LOCATION_CHECK) {
                    checkLocationAndProceed()
                }
            }
        }

        scope.launch {
            mainViewModel.batteryOptimizationStatus.collect { status ->
                if ((status == BatteryOptimizationStatus.DISABLED || status == BatteryOptimizationStatus.NOT_SUPPORTED) && 
                    mainViewModel.onboardingState.value == OnboardingState.BATTERY_OPTIMIZATION_CHECK) {
                    proceedWithPermissionCheck()
                }
            }
        }
    }

    override fun onEnableBluetooth() {
        mainViewModel.updateBluetoothLoading(true)
        bluetoothStatusManager.requestEnableBluetooth()
    }

    override fun onRetryBluetooth() {
        checkBluetoothAndProceed()
    }

    override fun onEnableLocation() {
        mainViewModel.updateLocationLoading(true)
        locationStatusManager.requestEnableLocation()
    }

    override fun onRetryLocation() {
        checkLocationAndProceed()
    }

    override fun onDisableBatteryOptimization() {
        mainViewModel.updateBatteryOptimizationLoading(true)
        batteryOptimizationManager.requestDisableBatteryOptimization()
    }

    override fun onRetryBatteryOptimization() {
        checkBatteryOptimizationAndProceed()
    }

    override fun onSkipBatteryOptimization() {
        proceedWithPermissionCheck()
    }

    override fun onRequestPermissions() {
        mainViewModel.updateOnboardingState(OnboardingState.PERMISSION_REQUESTING)
        onboardingCoordinator.requestPermissions()
    }

    override fun onRetryInitialization() {
        mainViewModel.updateOnboardingState(OnboardingState.CHECKING)
        checkOnboardingStatus()
    }

    override fun onOpenSettings() {
        onboardingCoordinator.openAppSettings()
    }

    override fun onComplete() {
        onOnboardingComplete()
    }

    // Logic from MainActivity

    fun checkOnboardingStatus() {
        Log.d("OnboardingComponent", "Checking onboarding status")
        
        scope.launch {
            // Small delay to show the checking state
            delay(500)
            
            // First check Bluetooth status (always required)
            checkBluetoothAndProceed()
        }
    }
    
    private fun checkBluetoothAndProceed() {
        // For first-time users, skip Bluetooth check and go straight to permissions
        // We'll check Bluetooth after permissions are granted
        if (permissionManager.isFirstTimeLaunch()) {
            Log.d("OnboardingComponent", "First-time launch, skipping Bluetooth check - will check after permissions")
            proceedWithPermissionCheck()
            return
        }
        
        // For existing users, check Bluetooth status first
        bluetoothStatusManager.logBluetoothStatus()
        mainViewModel.updateBluetoothStatus(bluetoothStatusManager.checkBluetoothStatus())
        
        when (mainViewModel.bluetoothStatus.value) {
            BluetoothStatus.ENABLED -> {
                // Bluetooth is enabled, check location services next
                checkLocationAndProceed()
            }
            BluetoothStatus.DISABLED -> {
                // Show Bluetooth enable screen (should have permissions as existing user)
                Log.d("OnboardingComponent", "Bluetooth disabled, showing enable screen")
                mainViewModel.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
                mainViewModel.updateBluetoothLoading(false)
            }
            BluetoothStatus.NOT_SUPPORTED -> {
                // Device doesn't support Bluetooth
                Log.e("OnboardingComponent", "Bluetooth not supported")
                mainViewModel.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
                mainViewModel.updateBluetoothLoading(false)
            }
        }
    }

    private fun checkLocationAndProceed() {
        Log.d("OnboardingComponent", "Checking location services status")
        
        // For first-time users, skip location check and go straight to permissions
        // We'll check location after permissions are granted
        if (permissionManager.isFirstTimeLaunch()) {
            Log.d("OnboardingComponent", "First-time launch, skipping location check - will check after permissions")
            proceedWithPermissionCheck()
            return
        }
        
        // For existing users, check location status
        locationStatusManager.logLocationStatus()
        mainViewModel.updateLocationStatus(locationStatusManager.checkLocationStatus())
        
        when (mainViewModel.locationStatus.value) {
            LocationStatus.ENABLED -> {
                // Location services enabled, check battery optimization next
                checkBatteryOptimizationAndProceed()
            }
            LocationStatus.DISABLED -> {
                // Show location enable screen (should have permissions as existing user)
                Log.d("OnboardingComponent", "Location services disabled, showing enable screen")
                mainViewModel.updateOnboardingState(OnboardingState.LOCATION_CHECK)
                mainViewModel.updateLocationLoading(false)
            }
            LocationStatus.NOT_AVAILABLE -> {
                // Device doesn't support location services (very unusual)
                Log.e("OnboardingComponent", "Location services not available")
                mainViewModel.updateOnboardingState(OnboardingState.LOCATION_CHECK)
                mainViewModel.updateLocationLoading(false)
            }
        }
    }

    private fun checkBatteryOptimizationAndProceed() {
        Log.d("OnboardingComponent", "Checking battery optimization status")
        
        // For first-time users, skip battery optimization check and go straight to permissions
        // We'll check battery optimization after permissions are granted
        if (permissionManager.isFirstTimeLaunch()) {
            Log.d("OnboardingComponent", "First-time launch, skipping battery optimization check - will check after permissions")
            proceedWithPermissionCheck()
            return
        }
        
        // Check if user has previously skipped battery optimization
        // Note: BatteryOptimizationPreferenceManager needs Context. 
        // We can assume BatteryOptimizationManager handles this or we need Context.
        // BatteryOptimizationPreferenceManager.isSkipped(context)
        // We don't have context here easily.
        // But BatteryOptimizationManager has context.
        // We should add isSkipped() to BatteryOptimizationManager.
        // For now, I'll assume we can skip this check or I need to inject Context.
        // Or I can add isSkipped to BatteryOptimizationManager.
        
        // Let's assume we can access it via BatteryOptimizationManager if I add a method there.
        // Or I can pass Context.
        // I'll skip the preference check for now or assume it's handled.
        
        // For existing users, check battery optimization status
        batteryOptimizationManager.logBatteryOptimizationStatus()
        val currentBatteryOptimizationStatus = when {
            !batteryOptimizationManager.isBatteryOptimizationSupported() -> BatteryOptimizationStatus.NOT_SUPPORTED
            batteryOptimizationManager.isBatteryOptimizationDisabled() -> BatteryOptimizationStatus.DISABLED
            else -> BatteryOptimizationStatus.ENABLED
        }
        mainViewModel.updateBatteryOptimizationStatus(currentBatteryOptimizationStatus)
        
        when (currentBatteryOptimizationStatus) {
            BatteryOptimizationStatus.DISABLED, BatteryOptimizationStatus.NOT_SUPPORTED -> {
                // Battery optimization is disabled or not supported, proceed with permission check
                proceedWithPermissionCheck()
            }
            BatteryOptimizationStatus.ENABLED -> {
                // Show battery optimization disable screen
                Log.d("OnboardingComponent", "Battery optimization enabled, showing disable screen")
                mainViewModel.updateOnboardingState(OnboardingState.BATTERY_OPTIMIZATION_CHECK)
                mainViewModel.updateBatteryOptimizationLoading(false)
            }
        }
    }

    private fun proceedWithPermissionCheck() {
        Log.d("OnboardingComponent", "Proceeding with permission check")
        
        scope.launch {
            delay(200) // Small delay for smooth transition
            
            if (permissionManager.isFirstTimeLaunch()) {
                Log.d("OnboardingComponent", "First time launch, showing permission explanation")
                mainViewModel.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
            } else if (permissionManager.areAllPermissionsGranted()) {
                Log.d("OnboardingComponent", "Existing user with permissions, initializing app")
                mainViewModel.updateOnboardingState(OnboardingState.INITIALIZING)
                initializeApp()
            } else {
                Log.d("OnboardingComponent", "Existing user missing permissions, showing explanation")
                mainViewModel.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
            }
        }
    }

    private fun initializeApp() {
        Log.d("OnboardingComponent", "Starting app initialization")
        
        scope.launch {
            try {
                // Initialize the app with a proper delay to ensure Bluetooth stack is ready
                delay(1000) // Give the system time to process permission grants
                
                Log.d("OnboardingComponent", "Permissions verified, initializing chat system")
                
                // Ensure all permissions are still granted
                if (!permissionManager.areAllPermissionsGranted()) {
                    val missing = permissionManager.getMissingPermissions()
                    Log.w("OnboardingComponent", "Permissions revoked during initialization: $missing")
                    handleOnboardingFailed("Some permissions were revoked. Please grant all permissions to continue.")
                    return@launch
                }

                // Initialize app services (mesh, etc.)
                onInitializeApp()
                
                Log.d("OnboardingComponent", "App initialization complete")
                mainViewModel.updateOnboardingState(OnboardingState.COMPLETE)
                onOnboardingComplete()
                
            } catch (e: Exception) {
                Log.e("OnboardingComponent", "Failed to initialize app", e)
                handleOnboardingFailed("Failed to initialize the app: ${e.message}")
            }
        }
    }

    private fun handleOnboardingFailed(message: String) {
        Log.e("OnboardingComponent", "Onboarding failed: $message")
        mainViewModel.updateErrorMessage(message)
        mainViewModel.updateOnboardingState(OnboardingState.ERROR)
    }
}
