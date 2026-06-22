package com.app.domain.usecase

import com.app.domain.repository.BatteryOptimizationRepository

/**
 * Optionally request the OS battery-optimization exemption that keeps the mesh foreground
 * service alive under aggressive OEM standby. Permissionless by design: a denial must never
 * block the app, so the prompt is shown at most once — gated on a persisted "asked" flag — and
 * only when the app is not already exempt. The flag is recorded before the prompt is launched,
 * so even an immediate dismissal never re-nags.
 */
class RequestBatteryExemptionOnceUseCase(
    private val repository: BatteryOptimizationRepository,
) {
    suspend operator fun invoke() {
        if (repository.isExempt()) return
        if (repository.hasAsked()) return
        repository.markAsked()
        repository.requestExemption()
    }
}
