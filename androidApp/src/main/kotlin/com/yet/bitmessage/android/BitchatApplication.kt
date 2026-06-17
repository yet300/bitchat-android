package com.yet.bitmessage.android

import android.app.Application
import com.yet.bitmessage.android.di.AndroidAppGraph
import com.yet.bitmessage.android.di.AppGraph
import dev.zacsweers.metro.createGraphFactory
import dev.zacsweers.metrox.android.MetroAppComponentProviders
import dev.zacsweers.metrox.android.MetroApplication
import com.app.transport.nostr.LocationNotesInitializer
import com.app.transport.nostr.NostrIdentityBridge

/**
 * Main application class for bitchat Android
 */
class BitchatApplication : Application(), MetroApplication {

    // Concrete graph held for MetroAppComponentFactory; exposed to the app as the AppGraph contract.
    private val androidAppGraph by lazy {
        createGraphFactory<AndroidAppGraph.Factory>().create(this)
    }

    val appGraph: AppGraph get() = androidAppGraph

    override val appComponentProviders: MetroAppComponentProviders get() = androidAppGraph

    override fun onCreate() {
        super.onCreate()

        // Initialize Tor first so any early network goes over Tor
        try {
            val torProvider = appGraph.artiTorManager
            torProvider.init()
            // Reconnect Nostr relays when Tor resets (wired here so ArtiTorManager stays unaware of nostr)
            torProvider.onConnectionsReset = {
                appGraph.nostrRelayManager.resetAllConnections()
            }
        } catch (_: Exception){}

        // Initialize relay directory (loads assets/nostr_relays.csv)
        appGraph.relayDirectory.initialize(this)

        // Initialize LocationNotesManager dependencies early so sheet subscriptions can start immediately
        try { LocationNotesInitializer.initialize(this, appGraph.relayDirectory, appGraph.nostrRelayManager, appGraph.locationNotesManager, appGraph.nostrIdentityBridge) } catch (_: Exception) { }

        // Warm up Nostr identity to ensure npub is available for favorite notifications
        try {
            appGraph.nostrIdentityBridge.getCurrentNostrIdentity()
        } catch (_: Exception) { }

        // Proactively start the foreground service to keep mesh alive
        try { com.yet.bitmessage.android.service.MeshForegroundService.start(this) } catch (_: Exception) { }

    }
}
