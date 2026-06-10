package com.bitchat.android.service

import android.content.Context
import com.app.transport.SeenMessageStore
import com.app.transport.mesh.TransferProgressManager
import com.app.transport.meshgraph.MeshGraphService
import com.app.transport.debug.DebugPreferenceManager
import com.app.transport.debug.DebugSettingsManager
import com.app.crypto.EncryptionService
import com.app.crypto.identity.PeerFingerprintManager
import com.app.transport.mesh.BluetoothMeshService
import com.bitchat.android.BitchatApplication

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
    fun getOrCreate(
        context: Context,
        debugSettingsManager: DebugSettingsManager,
        debugPreferenceManager: DebugPreferenceManager,
        seenMessageStore: SeenMessageStore,
        transferProgressManager: TransferProgressManager,
        meshGraphService: MeshGraphService,
        peerFingerprintManager: PeerFingerprintManager,
        encryptionService: EncryptionService,
    ): BluetoothMeshService {
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
                    val created = newMeshService(context, debugSettingsManager, debugPreferenceManager, seenMessageStore, transferProgressManager, meshGraphService, peerFingerprintManager, encryptionService)
                    android.util.Log.i(TAG, "Created new BluetoothMeshService (replacement)")
                    meshService = created
                    created
                }
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Error checking service reusability; creating new instance: ${e.message}")
                val created = newMeshService(context, debugSettingsManager, debugPreferenceManager, seenMessageStore, transferProgressManager, meshGraphService, peerFingerprintManager, encryptionService)
                meshService = created
                created
            }
        }
        val created = newMeshService(context, debugSettingsManager, debugPreferenceManager, seenMessageStore, transferProgressManager, meshGraphService, peerFingerprintManager, encryptionService)
        android.util.Log.i(TAG, "Created new BluetoothMeshService (no existing instance)")
        meshService = created
        return created
    }

    /**
     * Constructs a BMS with the app-side wiring SPIs resolved from the graph
     * ([com.bitchat.android.di.AndroidAppBindings]). Transitional: Stage 1.3 deletes this
     * holder and the graph constructs BMS directly.
     */
    private fun newMeshService(
        context: Context,
        debugSettingsManager: DebugSettingsManager,
        debugPreferenceManager: DebugPreferenceManager,
        seenMessageStore: SeenMessageStore,
        transferProgressManager: TransferProgressManager,
        meshGraphService: MeshGraphService,
        peerFingerprintManager: PeerFingerprintManager,
        encryptionService: EncryptionService,
    ): BluetoothMeshService {
        val appGraph = (context.applicationContext as BitchatApplication).appGraph
        return BluetoothMeshService(
            context.applicationContext,
            debugSettingsManager,
            debugPreferenceManager,
            seenMessageStore,
            transferProgressManager,
            meshGraphService,
            peerFingerprintManager,
            encryptionService,
            appGraph.serviceNotifier,
            appGraph.nicknameSource,
            appGraph.incomingMessageSink,
            appGraph.favoriteNostrLink,
            appGraph.geohashReadReceiptRouter,
        )
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
