package com.yet.bitmessage.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.createGraph

/**
 * The single iOS `@DependencyGraph`. Unlike [com.yet.bitmessage.android.di.AndroidAppGraph] it needs
 * no factory-provided platform instance (no Context on Apple targets) — every platform leaf is
 * self-constructed in the native binding containers (KSafe/Keychain stores, SQLCipher driver,
 * UIKit foreground state), all `@ContributesTo(AppScope)` and therefore aggregated automatically.
 */
@DependencyGraph(scope = AppScope::class)
interface IosAppGraph : AppGraph

private val graph: IosAppGraph by lazy { createGraph<IosAppGraph>() }

/**
 * Swift-facing accessor (`IosAppGraphKt.getIosAppGraph()`): Metro's `createGraph` is a Kotlin
 * compiler intrinsic, so the single app-scoped graph is assembled here and handed to the
 * AppDelegate, which owns the Decompose root built from it.
 */
fun getIosAppGraph(): IosAppGraph = graph
