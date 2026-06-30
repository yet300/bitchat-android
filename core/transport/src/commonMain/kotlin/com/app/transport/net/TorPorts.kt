package com.app.transport.net

/**
 * Supplies the on-disk data directory the Arti client uses for its state/cache, as an absolute
 * path. commonMain seam over the Android app's filesDir (the directory is created on demand by
 * the platform implementation); lets [ArtiTorManager] stay free of java.io.File / Context.
 */
fun interface TorDataDirProvider {
    fun artiDataDir(): String
}

/**
 * Drops the cached HTTP/WebSocket clients so they are rebuilt against the new Tor circuit.
 * commonMain seam over androidMain's HttpClientProvider.reset(), called by [ArtiTorManager]
 * whenever Tor network state changes.
 */
fun interface TorHttpReset {
    fun reset()
}
