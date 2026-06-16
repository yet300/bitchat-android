package com.bitchat.android.verification

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.app.domain.repository.CameraPermissionRepository
import com.bitchat.android.connectivity.PermissionOutcome
import com.bitchat.android.connectivity.RuntimePermissionRequester
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.time.Duration.Companion.milliseconds

/**
 * Android implementation of [CameraPermissionRepository] over the existing
 * [RuntimePermissionRequester]; permanent denial falls back to the app-settings deep link.
 */
class AndroidCameraPermissionRepository(
    private val context: Context,
    private val permissionRequester: RuntimePermissionRequester,
) : CameraPermissionRepository {

    override fun observeGranted(): Flow<Boolean> = flow {
        while (true) {
            emit(isGranted())
            delay(POLL_INTERVAL_MS.milliseconds)
        }
    }

    override suspend fun requestPermission() {
        if (isGranted()) return
        when (permissionRequester.request(listOf(Manifest.permission.CAMERA))) {
            PermissionOutcome.GRANTED, PermissionOutcome.DENIED -> Unit
            PermissionOutcome.PERMANENTLY_DENIED, PermissionOutcome.NO_HOST -> openAppSettings()
        }
    }

    private fun isGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            data = Uri.fromParts("package", context.packageName, null)
        }
        runCatching { context.startActivity(intent) }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1_000L
    }
}
