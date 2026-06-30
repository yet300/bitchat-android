package com.app.transport.mesh

/**
 * Narrow lifecycle contract for the mesh engine (ISP). The foreground service — the
 * lifecycle owner per project invariant — consumes this instead of the full
 * [BluetoothMeshService] surface.
 */
interface MeshLifecycleController {
    fun start()
    fun stop()
    /** Rotate the in-memory mesh identity (new peerID + Noise keys) and restart advertising/scanning. */
    fun reset()
    val isMeshActive: Boolean
    fun activePeerCount(): Int

    /**
     * Mark the mesh foreground service active/inactive. While active, BLE power management
     * treats the process as foreground even though ProcessLifecycleOwner reports background
     * (an FGS does not count as foreground), keeping peer discovery responsive with the
     * screen off. The service sets it true after promoting to foreground, false on teardown.
     */
    fun setMeshServiceActive(active: Boolean)
}
