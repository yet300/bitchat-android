package com.yet.bitmessage

import androidx.compose.ui.window.ComposeUIViewController
import com.arkivanov.decompose.ExperimentalDecomposeApi
import com.arkivanov.decompose.extensions.compose.stack.animation.predictiveback.PredictiveBackGestureOverlay
import com.arkivanov.essenty.backhandler.BackDispatcher
import com.yet.bitmessage.feature.root.RootComponent
import com.yet.bitmessage.ui.App

/**
 * iOS entry point, called from SwiftUI (`MainViewControllerKt.MainViewController()`). Creates the
 * app-scoped graph and the Decompose root bound to the application lifecycle — the root (and the
 * graph) live as long as the process, matching the single-Activity setup on Android.
 */
@OptIn(ExperimentalDecomposeApi::class)
fun MainViewController(
    root: RootComponent,
    backDispatcher: BackDispatcher
) = ComposeUIViewController {
    PredictiveBackGestureOverlay(
        backDispatcher = backDispatcher,
        backIcon = { progress, _ ->
        },
    ) {
        App(root)
    }
}