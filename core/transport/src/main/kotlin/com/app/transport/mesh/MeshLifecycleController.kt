package com.app.transport.mesh

/**
 * Narrow lifecycle contract for the mesh engine (ISP). The foreground service — the
 * lifecycle owner per project invariant — consumes this instead of the full
 * [BluetoothMeshService] surface.
 */
interface MeshLifecycleController {
    fun start()
    fun stop()
    val isMeshActive: Boolean
    fun activePeerCount(): Int
}
