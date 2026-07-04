package com.app.transport.features.file

import com.app.transport.model.BitchatFilePacket

/**
 * Persists an incoming file payload to local storage and returns the stored path/uri.
 *
 * commonMain seam over the Android `FileUtils` (which writes under the app cacheDir via an
 * Android `Context`); lets the mesh receive path ([com.app.transport.mesh.MessageHandler])
 * stay platform-free. An iOS implementation is a later follow-up.
 */
interface IncomingFileStore {
    fun saveIncomingFile(file: BitchatFilePacket): String
}
