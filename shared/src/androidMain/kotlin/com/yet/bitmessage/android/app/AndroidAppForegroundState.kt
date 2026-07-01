package com.yet.bitmessage.android.app

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.app.domain.app.AppForegroundState
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [AppForegroundState] backed by [ProcessLifecycleOwner]: onStart -> foreground, onStop -> background.
 *
 * The observer must be attached on the main thread; DI may construct this singleton lazily off-main,
 * so registration (and the initial value read) is posted to the main looper.
 */
@SingleIn(AppScope::class)
@Inject
class AndroidAppForegroundState : AppForegroundState {
    private val _isForeground = MutableStateFlow(false)
    override val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    init {
        Handler(Looper.getMainLooper()).post {
            val lifecycle = ProcessLifecycleOwner.get().lifecycle
            _isForeground.value = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) { _isForeground.value = true }
                override fun onStop(owner: LifecycleOwner) { _isForeground.value = false }
            })
        }
    }
}
