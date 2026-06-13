package com.bitchat.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.arkivanov.decompose.defaultComponentContext
import com.bitchat.android.di.appGraph
import com.yet.bitmessage.ui.App

/**
 * Phase C entry point for the new Decompose + Compose Multiplatform UI.
 *
 * Runs the :shared scaffold against the single application graph (the same
 * [com.bitchat.android.BitchatApplication.appGraph] the legacy [MainActivity] uses), so the
 * conversation list is backed by the live `ConversationRepository`. Registered as a separate
 * preview launcher while the legacy UI is still the default; both share one graph and one mesh
 * engine instance.
 */
class BitMessageActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // defaultComponentContext() must be obtained before setContent so Decompose binds to the
        // activity lifecycle / saved-state registry (config-change + process-death survival).
        val rootComponent = appGraph.rootFactory.create(defaultComponentContext())

        setContent {
            App(rootComponent = rootComponent)
        }
    }
}
