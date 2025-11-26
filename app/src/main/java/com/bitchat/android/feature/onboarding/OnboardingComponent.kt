package com.bitchat.android.feature.onboarding

interface OnboardingComponent {
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
}
