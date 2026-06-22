package com.app.domain.repository

/**
 * OS battery-optimization exemption for the mesh foreground service. Aggressive OEM standby
 * (Galaxy and others) can kill an exempt-less FGS; requesting the exemption keeps the mesh alive.
 *
 * Context-free port — the Android implementation lives in :shared androidMain, mirroring the
 * other permission repositories. The "asked once" flag is persisted so the optional prompt is
 * never shown more than once. Implementations report [isExempt] = true where the concept does
 * not apply (e.g. pre-Android-M).
 */
interface BatteryOptimizationRepository {

    /** Whether the app is already exempt from OS battery optimization (or exemption is irrelevant). */
    fun isExempt(): Boolean

    /** Whether the one-time exemption prompt has already been shown to the user. */
    fun hasAsked(): Boolean

    /** Persist that the exemption prompt has been shown, so it is never shown again. */
    fun markAsked()

    /** Launch the OS battery-optimization exemption request. No-op where unsupported. */
    suspend fun requestExemption()
}
