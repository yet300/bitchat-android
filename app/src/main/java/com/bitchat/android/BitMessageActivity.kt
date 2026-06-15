package com.bitchat.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.arkivanov.decompose.defaultComponentContext
import com.bitchat.android.connectivity.PermissionOutcome
import com.bitchat.android.connectivity.RuntimePermissionRequester
import com.bitchat.android.di.appGraph
import com.yet.bitmessage.ui.App
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase C entry point for the new Decompose + Compose Multiplatform UI.
 *
 * Runs the :shared scaffold against the single application graph (the same
 * [com.bitchat.android.BitchatApplication.appGraph] the legacy [MainActivity] uses), so the
 * conversation list is backed by the live `ConversationRepository`. Registered as a separate
 * preview launcher while the legacy UI is still the default; both share one graph and one mesh
 * engine instance.
 *
 * Also bridges the data layer's connectivity repair to an in-app system permission dialog: it
 * attaches a [RuntimePermissionRequester.Host] backed by an ActivityResult launcher for the
 * Activity's lifetime, so "Grant" in the connectivity sheet shows the OS dialog rather than always
 * deep-linking to app settings.
 */
class BitMessageActivity : ComponentActivity() {

    private var pendingPermission: CompletableDeferred<Map<String, Boolean>>? = null

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

        // defaultComponentContext() must be obtained before setContent so Decompose binds to the
        // activity lifecycle / saved-state registry (config-change + process-death survival).
        val rootComponent = appGraph.rootFactory.create(defaultComponentContext())

        setContent {
            App(rootComponent = rootComponent)
        }
    }

    override fun onDestroy() {
        appGraph.runtimePermissionRequester.detach(permissionHost)
        super.onDestroy()
    }
}
