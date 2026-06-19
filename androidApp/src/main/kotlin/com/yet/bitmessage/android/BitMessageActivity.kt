package com.yet.bitmessage.android

import android.content.Intent
import android.os.Bundle
import com.app.common.utils.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.app.domain.model.ConversationId
import com.app.domain.model.PeerId
import com.app.domain.usecase.ParseGeohashUseCase
import com.arkivanov.decompose.defaultComponentContext
import com.yet.bitmessage.android.connectivity.PermissionOutcome
import com.yet.bitmessage.android.connectivity.RuntimePermissionRequester
import com.yet.bitmessage.android.di.appGraph
import com.yet.bitmessage.android.service.MeshForegroundService
import com.yet.bitmessage.android.ui.NotificationManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.yet.bitmessage.feature.root.RootComponent
import com.yet.bitmessage.ui.App
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's launcher entry: the Decompose + Compose Multiplatform UI from :shared, bound to the
 * single application graph ([com.yet.bitmessage.android.BitchatApplication.appGraph]).
 *
 * It also:
 * - bridges the data layer's connectivity repair to an in-app system permission dialog via a
 *   [RuntimePermissionRequester.Host] backed by an ActivityResult launcher;
 * - starts the mesh foreground service in a lifecycle-aware, best-effort way (deferred to onStart
 *   when the process can't yet promote to foreground — avoids ForegroundServiceStartNotAllowed on
 *   Android 12+; upstream parity #714). The service is the foreground owner of the mesh lifecycle.
 */
class BitMessageActivity : ComponentActivity() {

    private lateinit var rootComponent: RootComponent
    private var pendingPermission: CompletableDeferred<Map<String, Boolean>>? = null
    private var pendingMeshForegroundServiceStart = false

    // Must be registered before the Activity is STARTED, hence a field initializer.
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            pendingPermission?.complete(result)
            pendingPermission = null
        }

    private val permissionHost = object : RuntimePermissionRequester.Host {
        override suspend fun request(permissions: List<String>): PermissionOutcome =
            withContext(Dispatchers.Main.immediate) {
                if (permissions.isEmpty()) return@withContext PermissionOutcome.GRANTED
                val deferred = CompletableDeferred<Map<String, Boolean>>()
                pendingPermission = deferred
                permissionLauncher.launch(permissions.toTypedArray())
                val result = deferred.await()
                when {
                    result.values.all { it } -> PermissionOutcome.GRANTED
                    // Rationale still allowed ⇒ the OS will show the dialog again on retry.
                    permissions.any { shouldShowRequestPermissionRationale(it) } -> PermissionOutcome.DENIED
                    else -> PermissionOutcome.PERMANENTLY_DENIED
                }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        appGraph.runtimePermissionRequester.attach(permissionHost)

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

    override fun onDestroy() {
        appGraph.runtimePermissionRequester.detach(permissionHost)
        super.onDestroy()
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
