package com.app.common.permission

import dev.brewkits.grant.AppGrant
import dev.brewkits.grant.GrantManager
import dev.brewkits.grant.GrantStatus
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds

/**
 * [PermissionController] over Grant (headless KMP permissions). Maps the app's [AppPermission] to
 * Grant's [AppGrant] and owns the system dialog / app-settings fallback, so callers stay free of any
 * platform permission types. Grant runs the system dialog from its own transparent Activity — no
 * Context or ActivityResult plumbing leaks up here. The [GrantManager] is built at the platform DI
 * edge (androidMain `GrantFactory.create`).
 *
 * `observeGranted` polls [GrantManager.checkStatus] while collected (the permission panels are
 * short-lived); a permanently-denied request routes the user to the app-settings screen.
 */
@SingleIn(AppScope::class)
@Inject
class GrantPermissionController(
    private val grant: GrantManager,
) : PermissionController {

    override fun observeGranted(permission: AppPermission): Flow<Boolean> = flow {
        val grantPermission = permission.toGrant()
        while (true) {
            emit(grant.checkStatus(grantPermission) == GrantStatus.GRANTED)
            delay(POLL_INTERVAL_MS.milliseconds)
        }
    }

    override suspend fun requestPermission(permission: AppPermission) {
        val grantPermission = permission.toGrant()
        if (grant.checkStatus(grantPermission) == GrantStatus.GRANTED) return
        if (grant.request(grantPermission) == GrantStatus.DENIED_ALWAYS) grant.openSettings()
    }

    private fun AppPermission.toGrant(): AppGrant = when (this) {
        AppPermission.Camera -> AppGrant.CAMERA
        AppPermission.Notifications -> AppGrant.NOTIFICATION
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1_000L
    }
}
