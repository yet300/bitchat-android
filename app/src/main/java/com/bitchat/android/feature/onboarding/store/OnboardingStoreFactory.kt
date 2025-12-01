package com.bitchat.android.feature.onboarding.store

import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import com.bitchat.android.onboarding.BatteryOptimizationManager
import com.bitchat.android.onboarding.BatteryOptimizationStatus
import com.bitchat.android.onboarding.BluetoothStatus
import com.bitchat.android.onboarding.BluetoothStatusManager
import com.bitchat.android.onboarding.LocationStatus
import com.bitchat.android.onboarding.LocationStatusManager
import com.bitchat.android.onboarding.OnboardingState
import com.bitchat.android.onboarding.PermissionManager
import kotlinx.coroutines.launch

internal class OnboardingStoreFactory(
    private val storeFactory: StoreFactory,
    private val bluetoothStatusManager: BluetoothStatusManager,
    private val locationStatusManager: LocationStatusManager,
    private val batteryOptimizationManager: BatteryOptimizationManager,
    private val permissionManager: PermissionManager
) {

    fun create(): OnboardingStore =
        object : OnboardingStore,
            Store<OnboardingStore.Intent, OnboardingStore.State, OnboardingStore.Label> by storeFactory.create(
                name = "OnboardingStore",
                initialState = OnboardingStore.State(permissionCategories = permissionManager.getCategorizedPermissions()),
                bootstrapper = SimpleBootstrapper(OnboardingStore.Action.Init),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl
            ) {}


    private inner class ExecutorImpl :
        CoroutineExecutor<OnboardingStore.Intent, OnboardingStore.Action, OnboardingStore.State, OnboardingStore.Msg, OnboardingStore.Label>() {

        override fun executeAction(action: OnboardingStore.Action) {
            when (action) {
                OnboardingStore.Action.Init -> {
                    // Load permission categories
                    val categories = permissionManager.getCategorizedPermissions()
                    dispatch(OnboardingStore.Msg.PermissionCategoriesLoaded(categories))
                    
                    // Subscribe to manager status changes
                    scope.launch {
                        bluetoothStatusManager.status.collect { status ->
                            dispatch(OnboardingStore.Msg.BluetoothStatusChanged(status))
                            checkState()
                        }
                    }
                    scope.launch {
                        locationStatusManager.status.collect { status ->
                            dispatch(OnboardingStore.Msg.LocationStatusChanged(status))
                            checkState()
                        }
                    }
                    scope.launch {
                        batteryOptimizationManager.status.collect { status ->
                            dispatch(OnboardingStore.Msg.BatteryStatusChanged(status))
                            checkState()
                        }
                    }
                    // Initial state check
                    checkState()
                }
            }
        }

        override fun executeIntent(intent: OnboardingStore.Intent) {
            when (intent) {
                OnboardingStore.Intent.EnableBluetooth -> {
                    dispatch(OnboardingStore.Msg.LoadingChanged(bluetooth = true))
                    publish(OnboardingStore.Label.RequestEnableBluetooth)
                }

                OnboardingStore.Intent.EnableLocation -> {
                    dispatch(OnboardingStore.Msg.LoadingChanged(location = true))
                    publish(OnboardingStore.Label.RequestEnableLocation)
                }

                OnboardingStore.Intent.DisableBatteryOptimization -> {
                    dispatch(OnboardingStore.Msg.LoadingChanged(battery = true))
                    publish(OnboardingStore.Label.RequestDisableBatteryOptimization)
                }

                OnboardingStore.Intent.SkipBatteryOptimization -> {
                    // User chose to skip battery optimization
                    // Mark battery as "handled" by setting loading to false
                    dispatch(OnboardingStore.Msg.LoadingChanged(battery = false))
                    // Proceed to next step (permissions)
                    proceedToPermissions()
                }

                OnboardingStore.Intent.RequestPermissions -> {
                    dispatch(OnboardingStore.Msg.OnboardingStateChanged(OnboardingState.PERMISSION_REQUESTING))
                    publish(OnboardingStore.Label.RequestPermissions)
                }

                OnboardingStore.Intent.Retry -> {
                    // Clear any error message
                    dispatch(OnboardingStore.Msg.Error(""))
                    // Reset loading states
                    dispatch(OnboardingStore.Msg.LoadingChanged(
                        bluetooth = false,
                        location = false,
                        battery = false
                    ))
                    // Re-check the current state to determine next step
                    checkState()
                }

                OnboardingStore.Intent.OpenSettings -> {
                    publish(OnboardingStore.Label.OpenSettings)
                }

                OnboardingStore.Intent.CheckStatus -> {
                    // Re-evaluate the onboarding state (e.g., after permissions change)
                    checkState()
                }
            }
        }

        /**
         * Determines the next onboarding state based on current manager statuses
         */
        private fun checkState() {
            val state = state()

            // If this is first time launch, skip hardware checks and go straight to permissions
            if (permissionManager.isFirstTimeLaunch()) {
                // On first launch, we need permissions before we can check hardware status
                // (Bluetooth check requires BLUETOOTH_CONNECT permission on Android 12+)
                if (state.onboardingState == OnboardingState.CHECKING) {
                    proceedToPermissions()
                } else if (state.onboardingState == OnboardingState.PERMISSION_REQUESTING ||
                           state.onboardingState == OnboardingState.PERMISSION_EXPLANATION) {
                    // Re-check if permissions are now granted (called after permission results)
                    proceedToPermissions()
                }
                return
            }

            // Check Bluetooth status
            if (state.bluetoothStatus != BluetoothStatus.ENABLED) {
                dispatch(OnboardingStore.Msg.OnboardingStateChanged(OnboardingState.BLUETOOTH_CHECK))
                dispatch(OnboardingStore.Msg.LoadingChanged(bluetooth = false))
                return
            }

            // Check Location status
            if (state.locationStatus != LocationStatus.ENABLED) {
                dispatch(OnboardingStore.Msg.OnboardingStateChanged(OnboardingState.LOCATION_CHECK))
                dispatch(OnboardingStore.Msg.LoadingChanged(location = false))
                return
            }

            // Check Battery Optimization status (optional, can be skipped)
            if (state.batteryStatus == BatteryOptimizationStatus.ENABLED) {
                dispatch(OnboardingStore.Msg.OnboardingStateChanged(OnboardingState.BATTERY_OPTIMIZATION_CHECK))
                dispatch(OnboardingStore.Msg.LoadingChanged(battery = false))
                return
            }

            // All hardware checks passed, proceed to permissions
            proceedToPermissions()
        }

        /**
         * Proceeds to permission check or completes onboarding
         */
        private fun proceedToPermissions() {
            if (permissionManager.areAllPermissionsGranted()) {
                permissionManager.markOnboardingComplete()
                dispatch(OnboardingStore.Msg.OnboardingStateChanged(OnboardingState.INITIALIZING))
                publish(OnboardingStore.Label.OnboardingComplete)
            } else {
                // Show permission explanation screen
                dispatch(OnboardingStore.Msg.OnboardingStateChanged(OnboardingState.PERMISSION_EXPLANATION))
            }
        }
    }

    private object ReducerImpl : Reducer<OnboardingStore.State, OnboardingStore.Msg> {
        override fun OnboardingStore.State.reduce(msg: OnboardingStore.Msg): OnboardingStore.State =
            when (msg) {
                is OnboardingStore.Msg.BluetoothStatusChanged -> copy(bluetoothStatus = msg.status)
                is OnboardingStore.Msg.LocationStatusChanged -> copy(locationStatus = msg.status)
                is OnboardingStore.Msg.BatteryStatusChanged -> copy(batteryStatus = msg.status)
                is OnboardingStore.Msg.OnboardingStateChanged -> copy(onboardingState = msg.state)
                is OnboardingStore.Msg.LoadingChanged -> copy(
                    isBluetoothLoading = msg.bluetooth ?: isBluetoothLoading,
                    isLocationLoading = msg.location ?: isLocationLoading,
                    isBatteryLoading = msg.battery ?: isBatteryLoading
                )

                is OnboardingStore.Msg.Error -> copy(errorMessage = msg.message)
                is OnboardingStore.Msg.PermissionCategoriesLoaded -> copy(permissionCategories = msg.categories)
            }
    }
}
