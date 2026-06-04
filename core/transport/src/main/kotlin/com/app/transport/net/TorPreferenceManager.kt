package com.app.transport.net

import android.content.Context
import com.app.common.appSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object TorPreferenceManager {
    private const val KEY_TOR_MODE = "tor_mode"

    private val _modeFlow = MutableStateFlow(TorMode.ON)
    val modeFlow: StateFlow<TorMode> = _modeFlow

    fun init(context: Context) {
        _modeFlow.value = read(context)
    }

    fun set(context: Context, mode: TorMode) {
        appSettings(context).putString(KEY_TOR_MODE, mode.name)
        _modeFlow.value = mode
    }

    fun get(context: Context): TorMode = read(context)

    private fun read(context: Context): TorMode {
        val saved = appSettings(context).getString(KEY_TOR_MODE, TorMode.ON.name)
        return runCatching { TorMode.valueOf(saved) }.getOrDefault(TorMode.ON)
    }
}
