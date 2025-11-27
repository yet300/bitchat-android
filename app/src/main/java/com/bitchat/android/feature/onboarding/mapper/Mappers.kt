package com.bitchat.android.feature.onboarding.mapper

import com.bitchat.android.feature.onboarding.OnboardingComponent
import com.bitchat.android.feature.onboarding.store.OnboardingStore

internal val onboardingStoreStateToModel: (OnboardingStore.State) -> OnboardingComponent.Model =
    { state ->
        OnboardingComponent.Model(
            onboardingState = state.onboardingState,
            bluetoothStatus = state.bluetoothStatus,
            locationStatus = state.locationStatus,
            batteryStatus = state.batteryStatus,
            isBluetoothLoading = state.isBluetoothLoading,
            isLocationLoading = state.isLocationLoading,
            isBatteryLoading = state.isBatteryLoading,
            errorMessage = state.errorMessage,
            permissionCategories = state.permissionCategories
        )
    }