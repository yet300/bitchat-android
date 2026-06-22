package com.yet.bitmessage.android.power

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.app.common.settings.SettingsStore
import com.app.domain.repository.BatteryOptimizationRepository

/**
 * Android implementation of [BatteryOptimizationRepository].
 *
 * Exemption is irrelevant before Android M, so [isExempt] reports `true` there. The request uses
 * the direct ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog (legitimate for a background mesh
 * foreground service); a denial simply leaves the app un-exempt — never blocking, per the
 * permissionless design. The "asked" flag is persisted via [SettingsStore].
 */
class AndroidBatteryOptimizationRepository(
    private val context: Context,
    private val settings: SettingsStore,
) : BatteryOptimizationRepository {

    override fun isExempt(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    override fun hasAsked(): Boolean = settings.getBoolean(KEY_ASKED, false)

    override fun markAsked() = settings.putBoolean(KEY_ASKED, true)

    // The direct request is intentional: this is a background mesh FGS, the user-facing case the
    // exemption exists for. Denial is harmless (the app stays un-exempt), so lint's policy warning
    // does not apply.
    @SuppressLint("BatteryLife")
    override suspend fun requestExemption() {
        if (isExempt()) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    private companion object {
        const val KEY_ASKED = "battery_optimization_exemption_asked"
    }
}
