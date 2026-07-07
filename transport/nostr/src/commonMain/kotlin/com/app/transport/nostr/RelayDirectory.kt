@file:OptIn(ExperimentalTime::class)

package com.app.transport.nostr

import com.app.common.AppDispatchers
import com.app.common.utils.Log
import com.app.common.geohash.Geohash
import com.app.transport.net.HttpClientProvider
import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Loads relay coordinates and provides nearest-relay lookup by geohash.
 *
 * App-scoped singleton: the last-update timestamp is persisted through the [NostrConfigStore]
 * port; the relay list lives in memory and is (re)loaded by [initialize]. File access goes through
 * kotlinx-io's [SystemFileSystem]; the bundled CSV and the cache path come from the platform
 * [RelayDirectoryStorage] seam, so this stays platform-free.
 */
class RelayDirectory(
    private val store: NostrConfigStore,
    private val httpClientProvider: HttpClientProvider,
    private val storage: RelayDirectoryStorage,
    private val dispatchers: AppDispatchers = AppDispatchers(),
) : GeohashRelaySource {

    private val tag = "RelayDirectory"
    private val assetFileUrl = "https://raw.githubusercontent.com/permissionlesstech/georelays/refs/heads/main/nostr_relays.csv"
    private val oneDayMs = 24L * 60 * 60 * 1000
    private val oneMinuteMs = 60L * 1000

    private val ioScope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val httpClient: HttpClient
        get() = httpClientProvider.httpClient()

    data class RelayInfo(
        val url: String,
        val latitude: Double,
        val longitude: Double
    )

    @Volatile
    private var initialized: Boolean = false

    private val relays: MutableList<RelayInfo> = mutableListOf()
    private val relaysLock = Lock()
    private val initLock = Lock()

    fun initialize() {
        if (initialized) return
        initLock.withLock {
            if (initialized) return
            try {
                val downloadedText = readTextOrNull(storage.downloadedCsvPath())
                val loadedFromDownloaded =
                    downloadedText != null && loadFromCsv(downloadedText, sourceLabel = "downloaded")

                if (!loadedFromDownloaded) {
                    loadFromCsv(storage.bundledRelayCsv(), sourceLabel = "bundled")
                }

                initialized = true

                // Trigger an immediate fetch if the data is stale (older than 24h)
                ioScope.launch {
                    if (isStale()) {
                        fetchAndMaybeSwap()
                    }
                }

                // Start periodic staleness check every minute
                startPeriodicRefresh()
            } catch (e: Exception) {
                Log.e(tag, "Failed to initialize RelayDirectory: ${e.message}")
            }
        }
    }

    /**
     * Return up to nRelays closest relay URLs to the geohash center.
     */
    override fun closestRelaysForGeohash(geohash: String, nRelays: Int): List<String> {
        val snapshot = relaysLock.withLock { relays.toList() }
        if (snapshot.isEmpty()) return emptyList()
        val center = try {
            Geohash.decodeToCenter(geohash)
        } catch (e: Exception) {
            Log.e(tag, "Failed to decode geohash '$geohash': ${e.message}")
            return emptyList()
        }

        val (lat, lon) = center
        return snapshot
            .asSequence()
            .sortedBy { haversineMeters(lat, lon, it.latitude, it.longitude) }
            .take(nRelays.coerceAtLeast(0))
            .map { it.url }
            .toList()
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0 // meters
        val dLat = (lat2 - lat1).toRadians()
        val dLon = (lon2 - lon1).toRadians()
        val a = sin(dLat / 2).pow(2.0) +
            cos(lat1.toRadians()) * cos(lat2.toRadians()) * sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    private fun Double.toRadians(): Double = this * PI / 180.0

    private fun normalizeRelayUrl(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed
        return if ("://" in trimmed) trimmed else "wss://$trimmed"
    }

    // ===== Implementation details =====

    private fun isStale(): Boolean {
        val last = store.getRelayLastUpdateMs(0L)
        val now = Clock.System.now().toEpochMilliseconds()
        return now - last >= oneDayMs
    }

    private fun startPeriodicRefresh() {
        ioScope.launch {
            while (true) {
                try {
                    if (isStale()) {
                        fetchAndMaybeSwap()
                    }
                } catch (e: Exception) {
                    Log.w(tag, "Periodic refresh encountered an error: ${e.message}")
                }
                delay(oneMinuteMs)
            }
        }
    }

    private suspend fun fetchAndMaybeSwap() {
        try {
            val bytes = downloadBytes(assetFileUrl)
            if (bytes == null) {
                Log.w(tag, "Failed to fetch latest relays; keeping current list")
                return
            }

            val parsed = parseCsv(bytes.decodeToString())
            if (parsed.isEmpty()) {
                Log.w(tag, "Downloaded relay CSV parsed to 0 entries; ignoring")
                return
            }

            val dest = storage.downloadedCsvPath()
            withContext(dispatchers.io) {
                SystemFileSystem.sink(dest).buffered().use { sink -> sink.write(bytes) }
            }

            relaysLock.withLock {
                relays.clear()
                relays.addAll(parsed)
            }

            store.setRelayLastUpdateMs(Clock.System.now().toEpochMilliseconds())
            Log.i(tag, "✅ Using downloaded relay list ($dest), entries=${parsed.size}")
        } catch (e: Exception) {
            Log.w(tag, "Failed to fetch and swap relay list: ${e.message}")
        }
    }

    private suspend fun downloadBytes(url: String): ByteArray? {
        return try {
            val response = httpClient.get(url)
            if (!response.status.isSuccess()) {
                Log.w(tag, "HTTP ${response.status.value} when fetching $url")
                return null
            }
            response.body()
        } catch (e: Exception) {
            Log.w(tag, "Download error: ${e.message}")
            null
        }
    }

    private fun loadFromCsv(csv: String, sourceLabel: String): Boolean {
        return try {
            val list = parseCsv(csv)
            if (list.isEmpty()) {
                Log.w(tag, "$sourceLabel relay CSV has 0 entries; ignoring")
                false
            } else {
                relaysLock.withLock {
                    relays.clear()
                    relays.addAll(list)
                }
                Log.i(tag, "📄 Loaded ${list.size} relay entries from $sourceLabel CSV")
                true
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed loading $sourceLabel relay CSV: ${e.message}")
            false
        }
    }

    private fun readTextOrNull(path: Path): String? = try {
        if (!SystemFileSystem.exists(path)) {
            null
        } else {
            SystemFileSystem.source(path).buffered().use { source -> source.readString() }
        }
    } catch (e: Exception) {
        Log.w(tag, "Failed reading relay CSV at $path: ${e.message}")
        null
    }

    private fun parseCsv(csv: String): List<RelayInfo> {
        val result = mutableListOf<RelayInfo>()
        for (rawLine in csv.lineSequence()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.lowercase().startsWith("relay url")) continue
            val parts = trimmed.split(",")
            if (parts.size < 3) continue
            val url = normalizeRelayUrl(parts[0].trim())
            val lat = parts[1].trim().toDoubleOrNull()
            val lon = parts[2].trim().toDoubleOrNull()
            if (url.isEmpty() || lat == null || lon == null) continue
            result.add(RelayInfo(url = url, latitude = lat, longitude = lon))
        }
        return result
    }
}
