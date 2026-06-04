package com.bitchat.android.service

import android.content.Context
import com.app.transport.nostr.GeohashAliasRegistry
import com.bitchat.android.mesh.BluetoothMeshService

/**
 * Process-wide holder to share a single BluetoothMeshService instance
 * between the foreground service and UI (MainActivity/ViewModels).
 */
object MeshServiceHolder {
    private const val TAG = "MeshServiceHolder"
    @Volatile
    var meshService: BluetoothMeshService? = null
        private set

    @Synchronized
    fun getOrCreate(context: Context): BluetoothMeshService {
        val existing = meshService
        if (existing != null) {
            // If the existing instance is healthy, reuse it; otherwise, replace it.
            return try {
                if (existing.isReusable()) {
                    android.util.Log.d(TAG, "Reusing existing BluetoothMeshService instance")
                    existing
                } else {
                    android.util.Log.w(TAG, "Existing BluetoothMeshService not reusable; replacing with a fresh instance")
                    // Best-effort stop before replacing
                    try { existing.stopServices() } catch (e: Exception) {
                        android.util.Log.w(TAG, "Error while stopping non-reusable instance: ${e.message}")
                    }
                    val created = configure(BluetoothMeshService(context.applicationContext), context)
                    android.util.Log.i(TAG, "Created new BluetoothMeshService (replacement)")
                    meshService = created
                    created
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error checking service reusability; creating new instance: ${e.message}")
                val created = configure(BluetoothMeshService(context.applicationContext), context)
                meshService = created
                created
            }
        }
        val created = configure(BluetoothMeshService(context.applicationContext), context)
        android.util.Log.i(TAG, "Created new BluetoothMeshService (no existing instance)")
        meshService = created
        return created
    }

    /** Wires app-side dependencies (notifications, nickname) into a fresh mesh service. */
    private fun configure(service: BluetoothMeshService, context: Context): BluetoothMeshService {
        val appCtx = context.applicationContext
        service.serviceNotifier = com.bitchat.android.ui.NotificationManager(
            appCtx,
            androidx.core.app.NotificationManagerCompat.from(appCtx),
            com.bitchat.android.util.NotificationIntervalManager()
        )
        service.nicknameSource = com.app.transport.NicknameSource { fallback ->
            com.bitchat.android.services.NicknameProvider.getNickname(appCtx, fallback)
        }
        // Process-wide in-memory store the UI hydrates from.
        service.incomingSink = com.bitchat.android.services.AppStateStore
        // Noise<->Nostr favorite mapping, backed by favorites persistence.
        service.favoriteNostrLink = object : com.app.transport.FavoriteNostrLink {
            override fun updatePeerFavoritedUs(noiseKey: ByteArray, theyFavoritedUs: Boolean) {
                com.bitchat.android.favorites.FavoritesPersistenceService.shared.updatePeerFavoritedUs(noiseKey, theyFavoritedUs)
            }
            override fun updateNostrPublicKey(noiseKey: ByteArray, nostrPubkey: String) {
                com.bitchat.android.favorites.FavoritesPersistenceService.shared.updateNostrPublicKey(noiseKey, nostrPubkey)
            }
            override fun updateNostrPublicKeyForPeerId(peerId: String, nostrPubkey: String) {
                com.bitchat.android.favorites.FavoritesPersistenceService.shared.updateNostrPublicKeyForPeerID(peerId, nostrPubkey)
            }
            override fun findNostrPubkey(noiseKey: ByteArray): String? =
                com.bitchat.android.favorites.FavoritesPersistenceService.shared.findNostrPubkey(noiseKey)
            override fun isFavorite(noiseKey: ByteArray): Boolean =
                com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)?.isFavorite == true
        }
        // Routes read receipts over the relay when the recipient is a geohash alias.
        service.geohashReadReceiptRouter = com.app.transport.GeohashReadReceiptRouter { messageId, toPeerId ->
            val router = runCatching { com.bitchat.android.services.MessageRouter.tryGetInstance() }.getOrNull()
            val isGeoAlias = runCatching {
                GeohashAliasRegistry.snapshot().containsKey(toPeerId)
            }.getOrDefault(false)
            if (isGeoAlias && router != null) {
                router.sendReadReceipt(com.app.transport.model.ReadReceipt(messageId), toPeerId)
                true
            } else {
                false
            }
        }
        return service
    }

    @Synchronized
    fun attach(service: BluetoothMeshService) {
        android.util.Log.d(TAG, "Attaching BluetoothMeshService to holder")
        meshService = service
    }

    @Synchronized
    fun clear() {
        android.util.Log.d(TAG, "Clearing BluetoothMeshService from holder")
        meshService = null
    }
}
