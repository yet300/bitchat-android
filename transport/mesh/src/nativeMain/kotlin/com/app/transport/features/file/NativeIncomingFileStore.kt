package com.app.transport.features.file

import com.app.common.utils.Log
import com.app.transport.model.BitchatFilePacket
import com.app.transport.platform.nativeCachesDirectory
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Apple [IncomingFileStore]: writes an incoming file payload under the caches directory and returns
 * its absolute path. The native counterpart of AndroidIncomingFileStore/FileUtils — same layout
 * (images/incoming vs files/incoming), mime→extension mapping, name sanitization and uniqueness —
 * but built on kotlinx-io's SystemFileSystem instead of java.io, so it stays free of an Android
 * Context. Caches dir is used (not a durable dir) so received media is system-purgeable.
 */
class NativeIncomingFileStore : IncomingFileStore {

    override fun saveIncomingFile(file: BitchatFilePacket): String {
        val lowerMime = file.mimeType.lowercase()
        val isImage = lowerMime.startsWith("image/")
        val baseDir = nativeCachesDirectory()
        val subdir = if (isImage) "images/incoming" else "files/incoming"
        val dir = Path(baseDir, subdir)
        SystemFileSystem.createDirectories(dir)

        val baseName = (file.fileName.takeIf { it.isNotBlank() } ?: (if (isImage) "img" else "file"))
            .replace(UNSAFE_CHARS, "_")
        val ext = extensionForMime(lowerMime, isImage)
        val initialName = if (baseName.contains('.')) baseName else baseName + ext
        val target = uniquePath(dir, initialName)

        return try {
            SystemFileSystem.sink(target).buffered().use { it.write(file.content) }
            target.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save incoming file to ${target}: ${e.message}")
            // Fallback to the caches root with the same uniqueness rule.
            val fallback = uniquePath(Path(baseDir), initialName)
            SystemFileSystem.sink(fallback).buffered().use { it.write(file.content) }
            fallback.toString()
        }
    }

    /** Append " (n)" before the extension until the path is free (mirrors FileUtils). */
    private fun uniquePath(dir: Path, name: String): Path {
        var candidate = name
        var idx = 1
        while (SystemFileSystem.exists(Path(dir.toString(), candidate)) && idx < MAX_NAME_ATTEMPTS) {
            val dot = name.lastIndexOf('.')
            candidate = if (dot > 0) {
                name.substring(0, dot) + " ($idx)" + name.substring(dot)
            } else {
                "$name ($idx)"
            }
            idx++
        }
        return Path(dir.toString(), candidate)
    }

    private fun extensionForMime(mime: String, isImage: Boolean): String = when (mime) {
        "image/jpeg", "image/jpg" -> ".jpg"
        "image/png" -> ".png"
        "image/webp" -> ".webp"
        "application/pdf" -> ".pdf"
        "text/plain" -> ".txt"
        else -> if (isImage) ".jpg" else ".bin"
    }

    private companion object {
        const val TAG = "NativeIncomingFileStore"
        const val MAX_NAME_ATTEMPTS = 1000
        val UNSAFE_CHARS = Regex("[^A-Za-z0-9._-]")
    }
}
