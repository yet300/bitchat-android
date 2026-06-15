package com.app.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Foreground-mesh background behaviour: auto-start on boot and run-in-background. The foreground
 * service and the boot receiver read the same preference keys.
 */
interface MeshSettingsRepository {

    fun observeAutoStart(): Flow<Boolean>

    fun observeBackgroundEnabled(): Flow<Boolean>

    suspend fun setAutoStart(enabled: Boolean)

    suspend fun setBackgroundEnabled(enabled: Boolean)
}
