package com.bitchat.android.di

import android.content.Context
import com.app.common.di.CommonBindings
import com.app.data.di.DataBindings
import com.yet.bitmessage.android.di.AndroidDataBindings
import com.yet.bitmessage.android.di.AndroidTransportBindings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.DependencyGraph
import dev.zacsweers.metro.Provides
import dev.zacsweers.metrox.android.MetroAppComponentProviders

/**
 * Application-scoped dependency graph. Aggregates the common, data and platform binding containers;
 * [Context] is supplied by the host [com.bitchat.android.BitchatApplication] through the factory.
 *
 * Also implements [MetroAppComponentProviders] so Android app components (Service/Activity/
 * Receiver/Provider) can be constructor-injected via `MetroAppComponentFactory` (minSdk 28+). The
 * provider maps are empty for now — every component still falls back to default instantiation — and
 * fill in as the framework components migrate onto the graph (Phase C).
 */
@DependencyGraph(
    scope = AppScope::class,
    bindingContainers = [
        CommonBindings::class,
        DataBindings::class,
        AndroidDataBindings::class,
        AndroidTransportBindings::class,
        AndroidAppBindings::class,
    ],
)
interface AndroidAppGraph : AppGraph, MetroAppComponentProviders {

    @DependencyGraph.Factory
    fun interface Factory {
        fun create(@Provides context: Context): AndroidAppGraph
    }
}
