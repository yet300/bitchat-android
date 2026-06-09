package com.bitchat.android.di

import android.content.Context
import com.app.common.appSettings
import com.app.crypto.EncryptionService
import com.app.crypto.identity.PeerFingerprintManager
import com.app.crypto.identity.SecureIdentityStateManager
import com.app.data.favorites.FavoritesPersistenceService
import com.app.data.routing.MessageRouter
import com.app.transport.mesh.BluetoothMeshService
import com.app.transport.nostr.GeohashAliasRegistry
import com.app.transport.nostr.GeohashConversationRegistry
import com.bitchat.android.service.MeshServiceHolder
import com.russhwolf.settings.ObservableSettings
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * Platform providers for the data layer's infrastructure dependencies (the leaf objects the
 * repository implementations in :core:data depend on). Each one delegates to the existing
 * process-wide singleton / holder so the graph and the still-living legacy paths share a *single*
 * instance during the Strangler-Fig transition — no duplicated mesh / favorites / identity state.
 * When the god-classes dissolve (Phase C) these holder/getInstance shims retire in favour of
 * graph-owned construction.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object AndroidDataBindings {

    @Provides
    @SingleIn(AppScope::class)
    fun provideObservableSettings(context: Context): ObservableSettings = appSettings(context)

    @Provides
    @SingleIn(AppScope::class)
    fun provideFavoritesPersistenceService(context: Context): FavoritesPersistenceService {
        FavoritesPersistenceService.initialize(context)
        return FavoritesPersistenceService.shared
    }

    @Provides
    @SingleIn(AppScope::class)
    fun providePeerFingerprintManager(): PeerFingerprintManager = PeerFingerprintManager.getInstance()

    @Provides
    @SingleIn(AppScope::class)
    fun provideEncryptionService(context: Context): EncryptionService = EncryptionService(context)

    @Provides
    @SingleIn(AppScope::class)
    fun provideSecureIdentityStateManager(context: Context): SecureIdentityStateManager =
        SecureIdentityStateManager(context)

    @Provides
    @SingleIn(AppScope::class)
    fun provideBluetoothMeshService(context: Context): BluetoothMeshService =
        MeshServiceHolder.getOrCreate(context)

    @Provides
    @SingleIn(AppScope::class)
    fun provideMessageRouter(
        context: Context,
        mesh: BluetoothMeshService,
        geohashConversationRegistry: GeohashConversationRegistry,
        geohashAliasRegistry: GeohashAliasRegistry,
    ): MessageRouter =
        MessageRouter.getInstance(context, mesh, geohashConversationRegistry, geohashAliasRegistry)
}
