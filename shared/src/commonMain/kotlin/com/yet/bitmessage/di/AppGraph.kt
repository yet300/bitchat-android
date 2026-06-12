package com.yet.bitmessage.di

import com.yet.bitmessage.feature.root.RootComponent

/**
 * Common contract of the dependency graph. The concrete platform `@DependencyGraph`
 * is built from :shared and aggregates the feature bindings together with the
 * data-layer bindings behind the domain ports.
 */
interface AppGraph {
    val rootFactory: RootComponent.Factory
}
