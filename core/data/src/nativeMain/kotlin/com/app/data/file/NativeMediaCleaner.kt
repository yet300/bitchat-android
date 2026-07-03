package com.app.data.file

import com.app.common.AppDispatchers
import com.app.domain.repository.MediaCleaner
import com.app.transport.platform.nativeCachesDirectory
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * [MediaCleaner] for Apple targets — the panic-wipe counterpart of `FileUtils.clearAllMedia`.
 * Deletes the incoming-media trees that `NativeIncomingFileStore` writes under the caches
 * directory (`images/incoming`, `files/incoming`).
 */
class NativeMediaCleaner(private val dispatchers: AppDispatchers) : MediaCleaner {

    override suspend fun wipeMedia() = withContext(dispatchers.io) {
        val base = nativeCachesDirectory()
        listOf("images/incoming", "files/incoming").forEach { subdir ->
            deleteRecursively(Path(base, subdir))
        }
    }

    private fun deleteRecursively(path: Path) {
        val meta = SystemFileSystem.metadataOrNull(path) ?: return
        if (meta.isDirectory) {
            SystemFileSystem.list(path).forEach(::deleteRecursively)
        }
        SystemFileSystem.delete(path, mustExist = false)
    }
}
