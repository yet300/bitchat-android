package com.app.transport.platform

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask

/**
 * App-private directory lookups for the Apple targets, the native counterpart of the Android
 * `Application` filesDir/cacheDir seams used by the transport's file/relay/Tor stores.
 *
 * Only the directory *roots* come from Foundation (NSSearchPath…); all file I/O is done with
 * kotlinx-io's SystemFileSystem, matching commonMain (RelayDirectory, BitchatFilePacket), so the
 * stores stay free of NSString/cinterop pointer juggling.
 */

/** Caches root (system-purgeable). Mirrors Android cacheDir; used for incoming files + relay cache. */
internal fun nativeCachesDirectory(): String =
    (NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String)
        ?: NSTemporaryDirectory()

/** Application-support root (persistent, not purged). Mirrors Android filesDir for durable state. */
internal fun nativeApplicationSupportDirectory(): String =
    (NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String)
        ?: NSTemporaryDirectory()

/**
 * Absolute path of the Arti/Tor data directory, created on demand. The native half of the
 * commonMain `TorDataDirProvider` seam (Android uses `File(filesDir, "arti")`). Lives under
 * application-support so the Tor state/cache persists across launches (Caches may be purged).
 */
fun nativeArtiDataDir(): String {
    val dir = Path(nativeApplicationSupportDirectory(), "arti")
    SystemFileSystem.createDirectories(dir)
    return dir.toString()
}
