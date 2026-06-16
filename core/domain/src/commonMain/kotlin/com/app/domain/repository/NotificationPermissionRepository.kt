package com.app.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Runtime notification permission status. On Android < 33, POST_NOTIFICATIONS is implicit and
 * [observeGranted] always emits `true`. The [requestPermission] action shows the system dialog
 * (or opens app settings when permanently denied); the result is reflected in the next emission.
 */
interface NotificationPermissionRepository {

    fun observeGranted(): Flow<Boolean>

    suspend fun requestPermission()
}
