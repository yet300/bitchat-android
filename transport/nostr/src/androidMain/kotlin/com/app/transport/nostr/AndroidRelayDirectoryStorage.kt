package com.app.transport.nostr

import android.content.Context
import kotlinx.io.files.Path

/**
 * Android [RelayDirectoryStorage]: the bundled CSV is read from the app assets, and the latest
 * downloaded CSV is cached under the app's filesDir. Constructed from the application [Context] in
 * the DI graph so the commonMain [RelayDirectory] never sees a Context.
 */
class AndroidRelayDirectoryStorage(private val context: Context) : RelayDirectoryStorage {

    override fun bundledRelayCsv(): String =
        context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }

    override fun downloadedCsvPath(): Path =
        Path(context.filesDir.absolutePath, DOWNLOADED_FILE)

    private companion object {
        const val ASSET_FILE = "nostr_relays.csv"
        const val DOWNLOADED_FILE = "nostr_relays_latest.csv"
    }
}
