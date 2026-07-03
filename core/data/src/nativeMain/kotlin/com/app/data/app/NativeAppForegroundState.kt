package com.app.data.app

import com.app.domain.app.AppForegroundState
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * [AppForegroundState] backed by UIKit lifecycle notifications: willEnterForeground -> foreground,
 * didEnterBackground -> background — the closest match to Android's ProcessLifecycleOwner
 * onStart/onStop (the brief inactive state during interruptions still counts as foreground).
 *
 * `UIApplication.applicationState` must be read on the main thread; DI may construct this singleton
 * lazily off-main, so the initial read (and observer registration) is dispatched to the main queue.
 * Observers are process-lifetime by design — this is an AppScope singleton, never disposed.
 */
@SingleIn(AppScope::class)
@Inject
class NativeAppForegroundState : AppForegroundState {
    private val _isForeground = MutableStateFlow(false)
    override val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    init {
        dispatch_async(dispatch_get_main_queue()) {
            _isForeground.value =
                UIApplication.sharedApplication.applicationState !=
                    UIApplicationState.UIApplicationStateBackground
            val center = NSNotificationCenter.defaultCenter
            val mainQueue = NSOperationQueue.mainQueue
            center.addObserverForName(
                name = UIApplicationWillEnterForegroundNotification,
                `object` = null,
                queue = mainQueue,
            ) { _ -> _isForeground.value = true }
            center.addObserverForName(
                name = UIApplicationDidEnterBackgroundNotification,
                `object` = null,
                queue = mainQueue,
            ) { _ -> _isForeground.value = false }
        }
    }
}
