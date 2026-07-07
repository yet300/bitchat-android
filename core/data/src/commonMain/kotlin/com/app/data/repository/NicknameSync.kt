package com.app.data.repository

import com.app.common.settings.SettingsStore
import com.app.database.dao.SecureSettingDao
import com.app.transport.NicknameHolder
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Startup bridge between the encrypted nickname setting and the transport-side [NicknameHolder]:
 * loads the persisted nickname into the holder before the mesh announces, keeps it in sync with
 * later changes, and deletes the legacy plaintext copy a past quick fix left in [SettingsStore].
 */
@SingleIn(AppScope::class)
@Inject
class NicknameSync internal constructor(
    private val secureSettings: SecureSettingDao,
    private val settings: SettingsStore,
    private val nicknameHolder: NicknameHolder,
    private val scope: CoroutineScope,
) {

    private var started = false

    /** Idempotent; called from the app-launch bootstrap. */
    fun start() {
        if (started) return
        started = true
        settings.remove(KEY_NICKNAME)
        scope.launch {
            secureSettings.observe(KEY_NICKNAME).collect { value ->
                value?.let(nicknameHolder::set)
            }
        }
    }
}
