package com.app.data.di

import android.content.Context
import com.app.transport.net.TorDataDirProvider
import com.app.transport.net.androidHttpClientEngineFactory
import com.app.transport.nostr.AndroidRelayDirectoryStorage
import com.app.transport.nostr.RelayDirectoryStorage
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import io.ktor.client.engine.HttpClientEngineFactory
import java.io.File

/**
 * Android-only transport-wiring providers: the app data dirs, the OkHttp ktor engine and the
 * asset-backed relay storage. The platform-free bridge providers (AppStateStore sink, favorites
 * link, routing sources) live in the commonMain [TransportDataBindings]; the app-resource-touching
 * providers (ServiceNotifier, NicknameSource) stay in the app-resident `AndroidAppBindings`.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object AndroidTransportBindings {

    /** Arti data dir under the app filesDir — the platform half of the Tor manager's data-dir seam. */
    @Provides
    fun provideTorDataDirProvider(context: Context): TorDataDirProvider =
        TorDataDirProvider {
            File(context.applicationContext.filesDir, "arti").apply { mkdirs() }.absolutePath
        }

    /** Relay CSV storage (bundled asset + filesDir cache) for the commonMain RelayDirectory. */
    @Provides
    fun provideRelayDirectoryStorage(context: Context): RelayDirectoryStorage =
        AndroidRelayDirectoryStorage(context.applicationContext)

    /**
     * Android ktor engine for the commonMain HttpClientProvider. OkHttp supports both WebSockets
     * (Nostr relays) and a SOCKS proxy (Arti/Tor).
     */
    @Provides
    fun provideHttpClientEngineFactory(): HttpClientEngineFactory<*> = androidHttpClientEngineFactory()

}
