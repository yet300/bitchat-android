package com.app.common.permission

import kotlinx.coroutines.flow.Flow

/**
 * Single app-wide seam for runtime permission status and requests — the permission counterpart of
 * [com.app.common.AppDispatchers]. Features inject this one port and gate on an [AppPermission] value;
 * the concrete implementation (Grant-backed, in :shared) maps to the platform permission and owns the
 * system dialog / app-settings fallback, so no platform permission types leak into feature code.
 */
interface PermissionController {

    /** Emits the current grant state of [permission] while collected. */
    fun observeGranted(permission: AppPermission): Flow<Boolean>

    /**
     * Shows the system dialog for [permission] (or opens app settings when permanently denied). The
     * result is reflected in the next [observeGranted] emission. No-op if already granted.
     */
    suspend fun requestPermission(permission: AppPermission)
}

sealed interface AppPermission {
    data object Camera : AppPermission
    data object Notifications : AppPermission
}