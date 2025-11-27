package com.bitchat.android.feature.onboarding.store

import com.arkivanov.mvikotlin.core.store.Store
import com.bitchat.android.onboarding.BatteryOptimizationStatus
import com.bitchat.android.onboarding.BluetoothStatus
import com.bitchat.android.onboarding.LocationStatus
import com.bitchat.android.onboarding.OnboardingState
import com.bitchat.android.onboarding.PermissionCategory

internal interface OnboardingStore :
    Store<OnboardingStore.Intent, OnboardingStore.State, OnboardingStore.Label> {

    data class State(
        val onboardingState: OnboardingState = OnboardingState.CHECKING,
        val bluetoothStatus: BluetoothStatus = BluetoothStatus.NOT_SUPPORTED,
        val locationStatus: LocationStatus = LocationStatus.NOT_AVAILABLE,
        val batteryStatus: BatteryOptimizationStatus = BatteryOptimizationStatus.NOT_SUPPORTED,
        val isBluetoothLoading: Boolean = false,
        val isLocationLoading: Boolean = false,
        val isBatteryLoading: Boolean = false,
        val errorMessage: String = "",
        val permissionCategories: List<PermissionCategory> = emptyList()
    )

    sealed class Intent {
        data object EnableBluetooth : Intent()
        data object EnableLocation : Intent()
        data object DisableBatteryOptimization : Intent()
        data object SkipBatteryOptimization : Intent()
        data object RequestPermissions : Intent()
        data object Retry : Intent()
        data object OpenSettings : Intent()
        data object CheckStatus : Intent()
    }

    sealed class Action {
        data object Init : Action()
    }

    sealed class Msg {
        data class BluetoothStatusChanged(val status: BluetoothStatus) : Msg()
        data class LocationStatusChanged(val status: LocationStatus) : Msg()
        data class BatteryStatusChanged(val status: BatteryOptimizationStatus) : Msg()
        data class OnboardingStateChanged(val state: OnboardingState) : Msg()
        data class LoadingChanged(
            val bluetooth: Boolean? = null,
            val location: Boolean? = null,
            val battery: Boolean? = null
        ) : Msg()

        data class PermissionCategoriesLoaded(val categories: List<PermissionCategory>) : Msg()

        data class Error(val message: String) : Msg()
    }

    sealed class Label {
        data object RequestEnableBluetooth : Label()
        data object RequestEnableLocation : Label()
        data object RequestDisableBatteryOptimization : Label()
        data object RequestPermissions : Label()
        data object OpenSettings : Label()
        data object OnboardingComplete : Label()
    }
}
