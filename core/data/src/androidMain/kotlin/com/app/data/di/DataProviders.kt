package com.app.data.di

import android.content.Context
import com.app.transport.features.file.AndroidIncomingFileStore
import com.app.transport.features.file.IncomingFileStore
import com.app.transport.mesh.BluetoothMeshService
import com.app.transport.mesh.MeshService
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * The only androidMain data binding container: it adapts the two concrete Android leaves the
 * platform-free [DataBindings] depends on — the [MeshService] port (implemented by
 * [BluetoothMeshService]) and the [IncomingFileStore] (backed by an Android `Context`). Everything
 * else (repository @Binds, routing @Provides) is commonMain now.
 *
 * Must stay public: an internal @ContributesTo container is invisible to the app graph aggregation.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object DataProviders {

    /** The commonMain mesh port, implemented by the androidMain [BluetoothMeshService]. */
    @Provides
    fun provideMeshService(mesh: BluetoothMeshService): MeshService = mesh

    /**
     * The `IncomingFileStore` seam (transport's commonMain port) used by `NostrDirectMessageIngest`
     * to persist incoming Nostr file transfers without holding an Android `Context` itself.
     */
    @Provides
    @SingleIn(AppScope::class)
    fun provideIncomingFileStore(context: Context): IncomingFileStore =
        AndroidIncomingFileStore(context.applicationContext)
}
