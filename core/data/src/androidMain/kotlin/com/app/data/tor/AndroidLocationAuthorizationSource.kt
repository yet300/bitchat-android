package com.app.data.tor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** Android [LocationAuthorizationSource]: fine or coarse location permission granted. */
class AndroidLocationAuthorizationSource(private val context: Context) : LocationAuthorizationSource {
    override fun isAuthorized(): Boolean {
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        return granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            granted(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}
