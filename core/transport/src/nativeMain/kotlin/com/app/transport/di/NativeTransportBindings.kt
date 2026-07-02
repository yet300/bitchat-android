package com.app.transport.di

import com.app.transport.features.file.IncomingFileStore
import com.app.transport.features.file.NativeIncomingFileStore
import com.app.transport.net.HttpClientProvider
import com.app.transport.net.TorDataDirProvider
import com.app.transport.net.WebSocketClientProvider
import com.app.transport.net.darwinHttpClientEngineFactory
import com.app.transport.nostr.NativeRelayDirectoryStorage
import com.app.transport.nostr.RelayDirectoryStorage
import com.app.transport.platform.nativeArtiDataDir
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Binds
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.ktor.client.engine.HttpClientEngineFactory


@ContributesTo(AppScope::class)
@BindingContainer
abstract class NativeTransportBindings {

    /** Binds the commonMain WebSocket seam to the shared HttpClientProvider. */
    @Binds
    abstract val HttpClientProvider.bindWebSocketClientProvider: WebSocketClientProvider

    companion object {
        /** Apple ktor engine for the commonMain HttpClientProvider (Darwin/NSURLSession). */
        @Provides
        fun provideHttpClientEngineFactory(): HttpClientEngineFactory<*> = darwinHttpClientEngineFactory()

        /** Relay CSV storage (bundle resource + caches-dir cache) for the commonMain RelayDirectory. */
        @Provides
        @SingleIn(AppScope::class)
        fun provideRelayDirectoryStorage(): RelayDirectoryStorage = NativeRelayDirectoryStorage()

        /** Incoming-file persistence under the caches dir (IncomingFileStore seam). */
        @Provides
        @SingleIn(AppScope::class)
        fun provideIncomingFileStore(): IncomingFileStore = NativeIncomingFileStore()

        /** Arti/Tor data dir under application-support — the native half of the TorDataDirProvider seam. */
        @Provides
        fun provideTorDataDirProvider(): TorDataDirProvider = TorDataDirProvider { nativeArtiDataDir() }
    }
}
