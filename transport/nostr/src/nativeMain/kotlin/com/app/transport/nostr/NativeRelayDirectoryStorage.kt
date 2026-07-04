package com.app.transport.nostr

import com.app.transport.platform.nativeCachesDirectory
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import platform.Foundation.NSBundle

/**
 * Apple [RelayDirectoryStorage]: the bundled CSV is read from the app bundle resource, and the
 * latest downloaded CSV is cached under the caches directory. The native counterpart of
 * AndroidRelayDirectoryStorage (assets + filesDir), so the commonMain [RelayDirectory] never sees a
 * platform context. File reads use kotlinx-io's SystemFileSystem, matching RelayDirectory itself.
 */
class NativeRelayDirectoryStorage : RelayDirectoryStorage {

    override fun bundledRelayCsv(): String {
        val resourcePath = NSBundle.mainBundle.pathForResource(BUNDLED_RESOURCE, BUNDLED_TYPE)
            ?: return ""
        val path = Path(resourcePath)
        if (!SystemFileSystem.exists(path)) return ""
        return SystemFileSystem.source(path).buffered().use { it.readString() }
    }

    override fun downloadedCsvPath(): Path =
        Path(nativeCachesDirectory(), DOWNLOADED_FILE)

    private companion object {
        const val BUNDLED_RESOURCE = "nostr_relays"
        const val BUNDLED_TYPE = "csv"
        const val DOWNLOADED_FILE = "nostr_relays_latest.csv"
    }
}
