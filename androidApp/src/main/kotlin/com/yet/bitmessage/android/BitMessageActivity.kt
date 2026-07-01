package com.yet.bitmessage.android

import android.content.Intent
import android.os.Build
import android.os.Bundle
import com.app.common.utils.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.app.domain.model.ConversationId
import com.app.domain.model.PeerId
import com.app.domain.usecase.ParseGeohashUseCase
import com.arkivanov.decompose.defaultComponentContext
import com.yet.bitmessage.android.di.appGraph
import com.yet.bitmessage.android.service.MeshForegroundService
import com.yet.bitmessage.android.ui.NotificationManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.yet.bitmessage.feature.root.RootComponent
import com.yet.bitmessage.ui.App
import kotlinx.coroutines.launch

/**
 * The app's launcher entry: the Decompose + Compose Multiplatform UI from :shared, bound to the
 * single application graph ([com.yet.bitmessage.android.BitchatApplication.appGraph]).
 *
 * It also:
 * - starts the mesh foreground service in a lifecycle-aware, best-effort way (deferred to onStart
 *   when the process can't yet promote to foreground — avoids ForegroundServiceStartNotAllowed on
 *   Android 12+; upstream parity #714). The service is the foreground owner of the mesh lifecycle.
 */
class BitMessageActivity : ComponentActivity() {

    private lateinit var rootComponent: RootComponent
    private var pendingMeshForegroundServiceStart = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Hide app content from the system recents screenshot (upstream #608, privacy).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }

        // Force eager creation so the verify coordinator attaches as the BMS verify listener.
        appGraph.peerVerificationRepository

        // defaultComponentContext() must be obtained before setContent so Decompose binds to the
        // activity lifecycle / saved-state registry (config-change + process-death survival).
        rootComponent = appGraph.rootFactory.create(defaultComponentContext())

        // Cold-start deep link from a notification tap (warm taps arrive via onNewIntent).
        intent?.let(::handleDeepLink)

        setContent {
            App(rootComponent = rootComponent)
        }

        startMeshForegroundServiceBestEffort()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    override fun onStart() {
        super.onStart()
        if (pendingMeshForegroundServiceStart) startMeshForegroundServiceBestEffort()
    }

    /**
     * Start the mesh foreground service only when the process may promote to foreground (the
     * activity is at least STARTED); otherwise defer to [onStart]. Eligibility (background pref /
     * notification permission) is enforced inside [MeshForegroundService.start].
     */
    private fun startMeshForegroundServiceBestEffort() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            pendingMeshForegroundServiceStart = true
            return
        }
        pendingMeshForegroundServiceStart = try {
            MeshForegroundService.start(applicationContext)
            false
        } catch (e: Exception) {
            Log.w("BitMessageActivity", "Deferring mesh foreground service start", e)
            true
        }
    }

    /**
     * Dispatch an inbound intent: a notification tap (shared [NotificationManager] extras) opens a
     * conversation; a `bitchat://verify` VIEW link starts the per-contact Noise verification.
     */
    private fun handleDeepLink(intent: Intent) {
        intent.toConversationId()?.let { rootComponent.openConversation(it) }
        intent.maybeStartVerification()
    }

    /**
     * A scanned/opened `bitchat://verify` link: validate the payload and begin the Noise challenge
     * against the matching peer. The result surfaces via the verified-fingerprint set (the chat
     * header indicator reacts), so this is fire-and-forget here.
     */
    private fun Intent.maybeStartVerification() {
        val uri = data ?: return
        if (uri.scheme != "bitchat" || uri.host != "verify") return
        val payload = uri.toString()
        lifecycleScope.launch {
            appGraph.peerVerificationRepository.verifyScannedQr(payload)
        }
    }

    private fun Intent.toConversationId(): ConversationId? = when {
        getBooleanExtra(NotificationManager.EXTRA_OPEN_PRIVATE_CHAT, false) ->
            getStringExtra(NotificationManager.EXTRA_PEER_ID)?.let { ConversationId.Private(PeerId(it)) }

        getBooleanExtra(NotificationManager.EXTRA_OPEN_GEOHASH_CHAT, false) ->
            getStringExtra(NotificationManager.EXTRA_GEOHASH)
                ?.let { ParseGeohashUseCase().invoke(it) }
                ?.let { ConversationId.Geohash(it) }

        else -> null
    }
}
