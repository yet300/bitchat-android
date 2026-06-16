package com.bitchat.android.notification

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.app.domain.repository.NotificationPermissionRepository
import com.bitchat.android.connectivity.PermissionOutcome
import com.bitchat.android.connectivity.RuntimePermissionRequester
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds

/**
 * Android implementation of [NotificationPermissionRepository].
 *
 * POST_NOTIFICATIONS is implicit on API < 33 — [observeGranted] always emits `true` there.
 * On API 33+ the system permission dialog is shown via [RuntimePermissionRequester]; permanent
 * denial falls back to the app-settings deep link.
 */
class AndroidNotificationPermissionRepository(
    private val context: Context,
    private val permissionRequester: RuntimePermissionRequester,
) : NotificationPermissionRepository {

    override fun observeGranted(): Flow<Boolean> = flow {
        while (true) {
            emit(isGranted())
            delay(POLL_INTERVAL_MS.milliseconds)
        }
    }

    override suspend fun requestPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (isGranted()) return
        when (permissionRequester.request(listOf(Manifest.permission.POST_NOTIFICATIONS))) {
            PermissionOutcome.GRANTED, PermissionOutcome.DENIED -> Unit
            PermissionOutcome.PERMANENTLY_DENIED, PermissionOutcome.NO_HOST -> openAppSettings()
        }
    }

    private fun isGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            data = Uri.fromParts("package", context.packageName, null)
        }
        runCatching { context.startActivity(intent) }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
    }
}
