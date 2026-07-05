package com.yet.bitmessage.di

import com.app.data.AppNetworkBootstrapper
import com.yet.bitmessage.feature.root.RootComponent

/**
 * Common contract of the dependency graph. The concrete platform `@DependencyGraph`
 * is built from :shared and aggregates the feature bindings together with the
 * data-layer bindings behind the domain ports.
 */
interface AppGraph {
    val rootFactory: RootComponent.Factory

    /**
     * Launch-time Nostr/Tor bootstrap. Exposed on the shared contract so both the Android
     * `Application` and the iOS `AppDelegate` run the identical sequence via a single hoisted
     * entry point (`start()`), rather than each duplicating it.
     */
    val appNetworkBootstrapper: AppNetworkBootstrapper
}
