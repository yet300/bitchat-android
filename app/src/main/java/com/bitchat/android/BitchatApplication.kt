package com.bitchat.android

import android.app.Application
import com.app.data.favorites.FavoritesPersistenceService
import com.app.transport.nostr.RelayDirectory
import com.bitchat.android.ui.theme.ThemePreferenceManager
import com.app.transport.net.ArtiTorManager
import com.app.transport.nostr.GeohashAliasRegistry
import com.app.transport.nostr.GeohashConversationRegistry
import com.app.transport.nostr.LocationNotesInitializer
import com.app.transport.nostr.NostrIdentityBridge
import com.app.transport.nostr.NostrRelayManager

/**
 * Main application class for bitchat Android
 */
class BitchatApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Tor first so any early network goes over Tor
        try {
            val torProvider = ArtiTorManager.getInstance()
            torProvider.init(this)
            // Reconnect Nostr relays when Tor resets (wired here so ArtiTorManager stays unaware of nostr)
            torProvider.onConnectionsReset = {
                NostrRelayManager.shared.resetAllConnections()
            }
        } catch (_: Exception){}

        // Initialize relay directory (loads assets/nostr_relays.csv)
        RelayDirectory.initialize(this)

        // Initialize LocationNotesManager dependencies early so sheet subscriptions can start immediately
        try { LocationNotesInitializer.initialize(this) } catch (_: Exception) { }

        // Initialize favorites persistence early so MessageRouter/NostrTransport can use it on startup
        try {
            FavoritesPersistenceService.initialize(this)
        } catch (_: Exception) { }

        // Warm up Nostr identity to ensure npub is available for favorite notifications
        try {
            NostrIdentityBridge.getCurrentNostrIdentity(this)
        } catch (_: Exception) { }

        // Initialize theme preference
        ThemePreferenceManager.init(this)

        // Initialize debug preference manager (persists debug toggles)
        try { com.app.transport.debug.DebugPreferenceManager.init(this) } catch (_: Exception) { }

        // Initialize Geohash Registries for persistence
        try {
            GeohashAliasRegistry.initialize(this)
            GeohashConversationRegistry.initialize(this)
        } catch (_: Exception) { }

        // Initialize mesh service preferences
        try { com.bitchat.android.service.MeshServicePreferences.init(this) } catch (_: Exception) { }

        // Proactively start the foreground service to keep mesh alive
        try { com.bitchat.android.service.MeshForegroundService.start(this) } catch (_: Exception) { }

        // TorManager already initialized above
    }
}
