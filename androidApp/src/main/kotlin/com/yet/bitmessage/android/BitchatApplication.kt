package com.yet.bitmessage.android

import android.app.Application
import com.yet.bitmessage.android.di.AndroidAppGraph
import com.yet.bitmessage.android.di.AppGraph
import dev.zacsweers.metro.createGraphFactory
import dev.zacsweers.metrox.android.MetroAppComponentProviders
import dev.zacsweers.metrox.android.MetroApplication

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

        // Launch-time Nostr/Tor bootstrap (Tor → relay-reset hook → relay directory → location notes
        // → identity warm-up → DM ingest). Hoisted into the shared AppNetworkBootstrapper so iOS runs
        // the identical sequence; order and per-step resilience are preserved there.
        appGraph.appNetworkBootstrapper.start()
    }
}
