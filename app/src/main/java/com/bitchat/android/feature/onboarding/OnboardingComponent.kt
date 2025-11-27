package com.bitchat.android.feature.onboarding

import com.arkivanov.decompose.value.Value
import com.bitchat.android.onboarding.BatteryOptimizationStatus
import com.bitchat.android.onboarding.BluetoothStatus
import com.bitchat.android.onboarding.LocationStatus
import com.bitchat.android.onboarding.OnboardingState
import com.bitchat.android.onboarding.PermissionCategory

interface OnboardingComponent {
    val model: Value<Model>

    fun onEnableBluetooth()
    fun onRetryBluetooth()

    fun onEnableLocation()
    fun onRetryLocation()

    fun onDisableBatteryOptimization()
    fun onRetryBatteryOptimization()
    fun onSkipBatteryOptimization()

    fun onRequestPermissions()

    fun onRetryInitialization()
    fun onOpenSettings()

    fun onComplete()

    data class Model(
        val onboardingState: OnboardingState,
        val bluetoothStatus: BluetoothStatus,
        val locationStatus: LocationStatus,
        val batteryStatus: BatteryOptimizationStatus,
        val isBluetoothLoading: Boolean,
        val isLocationLoading: Boolean,
        val isBatteryLoading: Boolean,
        val errorMessage: String,
        val permissionCategories: List<PermissionCategory>
    )
}
