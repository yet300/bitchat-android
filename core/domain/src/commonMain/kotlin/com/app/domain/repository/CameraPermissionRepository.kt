package com.app.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Runtime CAMERA permission status for the QR scanner. Mirrors
 * [NotificationPermissionRepository]: [requestPermission] shows the system dialog (or opens app
 * settings when permanently denied); the result is reflected in the next [observeGranted] emission.
 */
interface CameraPermissionRepository {

    fun observeGranted(): Flow<Boolean>

    suspend fun requestPermission()
}
