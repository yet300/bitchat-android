package com.yet.bitmessage.android.di

import com.app.data.AppStateStore
import com.app.data.MessageRetentionJob
import com.app.domain.repository.PeerVerificationRepository
import com.app.transport.SeenMessageStore
import com.app.transport.mesh.MeshLifecycleController
import com.app.transport.net.ArtiTorManager
import com.yet.bitmessage.android.service.MeshServicePreferences
import com.yet.bitmessage.di.AppGraph as SharedAppGraph

/**
 * Public API of the application dependency graph: the domain ports the app (and, in Phase C, the
 * Decompose component tree) resolves. The concrete graph is generated per platform
 * ([AndroidAppGraph]). Repository implementations stay internal to :core:data — only these domain
 * interfaces cross the module boundary (DIP).
 *
 * Extends the :shared [SharedAppGraph] contract so the Phase C Decompose entry point
 * ([com.yet.bitmessage.android.BitMessageActivity]) resolves `rootFactory` through the same single
 * graph. When the concrete graph relocates into :shared, this :app interface and its legacy
 * accessors retire.
 */
interface AppGraph : SharedAppGraph {
    val appStateStore: AppStateStore
    val messageRetentionJob: MessageRetentionJob
    val seenMessageStore: SeenMessageStore
    val artiTorManager: ArtiTorManager


    val meshServicePreferences: MeshServicePreferences

    val meshLifecycleController: MeshLifecycleController

    /**
     * Graph-owned QR-verification coordinator. Exposed so the Phase C entry can resolve it eagerly,
     * forcing it to attach as the BMS verify listener (so inbound challenges are answered even
     * before the user opens the scanner).
     */
    val peerVerificationRepository: PeerVerificationRepository
}