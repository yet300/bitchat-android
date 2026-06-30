package com.app.transport.features.file

import android.content.Context
import com.app.transport.model.BitchatFilePacket

/**
 * Android [IncomingFileStore] backed by [FileUtils], which stores incoming files under the
 * app cacheDir (eligible for automatic system cleanup). Constructed by the mesh service,
 * which owns the application [Context].
 */
class AndroidIncomingFileStore(private val context: Context) : IncomingFileStore {
    override fun saveIncomingFile(file: BitchatFilePacket): String =
        FileUtils.saveIncomingFile(context, file)
}
