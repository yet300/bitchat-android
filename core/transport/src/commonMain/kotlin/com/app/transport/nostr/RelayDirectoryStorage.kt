package com.app.transport.nostr

import kotlinx.io.files.Path

/**
 * Platform storage for [RelayDirectory]: the bundled fallback relay CSV (shipped as an app asset)
 * and the on-disk path where the latest downloaded CSV is cached. commonMain seam over the Android
 * `Application` (assets + filesDir); the file I/O itself is done with kotlinx-io's SystemFileSystem,
 * so the directory logic stays platform-free.
 */
interface RelayDirectoryStorage {
    /** Contents of the relay CSV bundled with the app (fallback when nothing is downloaded yet). */
    fun bundledRelayCsv(): String

    /** Absolute path of the cached "latest" relay CSV (read on start, overwritten on refresh). */
    fun downloadedCsvPath(): Path
}
