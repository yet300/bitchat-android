package com.bitchat.android.feature.onboarding

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.labels
import com.bitchat.android.core.common.asValue
import com.bitchat.android.core.common.coroutineScope
import com.bitchat.android.feature.onboarding.mapper.onboardingStoreStateToModel
import com.bitchat.android.feature.onboarding.store.OnboardingStore
import com.bitchat.android.feature.onboarding.store.OnboardingStoreFactory
import com.bitchat.android.onboarding.BatteryOptimizationManager
import com.bitchat.android.onboarding.BluetoothStatusManager
import com.bitchat.android.onboarding.LocationStatusManager
import com.bitchat.android.onboarding.OnboardingCoordinator
import com.bitchat.android.onboarding.PermissionManager
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class DefaultOnboardingComponent(
    componentContext: ComponentContext,
    private val bluetoothStatusManager: BluetoothStatusManager,
    private val locationStatusManager: LocationStatusManager,
    private val batteryOptimizationManager: BatteryOptimizationManager,
    private val onboardingCoordinator: OnboardingCoordinator,
    private val permissionManager: PermissionManager,
    private val onOnboardingComplete: () -> Unit
) : OnboardingComponent, ComponentContext by componentContext, KoinComponent {

    private val storeFactory: StoreFactory by inject()

    private val store = instanceKeeper.getStore {
        OnboardingStoreFactory(
            storeFactory = storeFactory,
            bluetoothStatusManager = bluetoothStatusManager,
            locationStatusManager = locationStatusManager,
            batteryOptimizationManager = batteryOptimizationManager,
            permissionManager = permissionManager
        ).create()
    }

    override val model: Value<OnboardingComponent.Model> = store.asValue().map(onboardingStoreStateToModel)

    // We need to register launchers here if possible, or assume they are set up by the Activity/Root.
    // Since we are in a Component, we can't easily register for ActivityResult without extensions.
    // However, the Managers now accept launchers. 
    // We can expose a method to set launchers, or rely on the Activity to set them on the Managers directly.
    // Given the prompt "Component should receive the Managers... via its constructor", 
    // and "Refactor BluetoothStatusManager... It should still handle the ActivityResultLauncher",
    // it's best if the Activity sets the launchers on the Managers.
    
    init {
        // Set up callback to re-check state after permissions are handled
        onboardingCoordinator.onPermissionsHandled = {
            store.accept(OnboardingStore.Intent.CheckStatus)
        }
        
        coroutineScope().launch {
            store.labels.collect { label ->
                when (label) {
                    OnboardingStore.Label.RequestEnableBluetooth -> bluetoothStatusManager.requestEnableBluetooth()
                    OnboardingStore.Label.RequestEnableLocation -> locationStatusManager.requestEnableLocation()
                    OnboardingStore.Label.RequestDisableBatteryOptimization -> batteryOptimizationManager.requestDisableBatteryOptimization()
                    OnboardingStore.Label.RequestPermissions -> onboardingCoordinator.requestPermissions()
                    OnboardingStore.Label.OpenSettings -> onboardingCoordinator.openAppSettings()
                    OnboardingStore.Label.OnboardingComplete -> onOnboardingComplete()
                }
            }
        }
    }

    override fun onEnableBluetooth() {
        store.accept(OnboardingStore.Intent.EnableBluetooth)
    }

    override fun onRetryBluetooth() {
        store.accept(OnboardingStore.Intent.Retry)
    }

    override fun onEnableLocation() {
        store.accept(OnboardingStore.Intent.EnableLocation)
    }

    override fun onRetryLocation() {
        store.accept(OnboardingStore.Intent.Retry)
    }

    override fun onDisableBatteryOptimization() {
        store.accept(OnboardingStore.Intent.DisableBatteryOptimization)
    }

    override fun onRetryBatteryOptimization() {
        store.accept(OnboardingStore.Intent.Retry)
    }

    override fun onSkipBatteryOptimization() {
        store.accept(OnboardingStore.Intent.SkipBatteryOptimization)
    }

    override fun onRequestPermissions() {
        store.accept(OnboardingStore.Intent.RequestPermissions)
    }

    override fun onRetryInitialization() {
        store.accept(OnboardingStore.Intent.Retry)
    }

    override fun onOpenSettings() {
        store.accept(OnboardingStore.Intent.OpenSettings)
    }

    override fun onComplete() {
        onOnboardingComplete()
    }
    
    // Called when permissions are updated (e.g. from Activity result)
    fun onPermissionsUpdated() {
        store.accept(OnboardingStore.Intent.CheckStatus)
    }
}
