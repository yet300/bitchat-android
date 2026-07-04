package com.app.transport.net

import com.app.common.AppDispatchers
import com.app.common.utils.Log
import com.app.transport.TorConstants
import com.yet.tor.ArtiConfig
import com.yet.tor.ArtiTorClient
import com.yet.tor.TorState as LibTorState
import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock as withCoroutineLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tor provider backed by the ArtiTor library (io.github.yet300:tor) — a KMP wrapper over Arti.
 *
 * The library bundles the native Tor runtime and exposes bootstrap progress as a first-class
 * [com.yet.tor.TorStatus] signal, so this manager no longer scrapes Arti log lines to infer state.
 * It keeps the retry / inactivity / port policy and the StateFlow surface that the rest of the app
 * (HttpClientProvider, settings UI) depends on.
 *
 * Platform seams: [TorDataDirProvider] supplies the Arti data dir (Android filesDir) and
 * [TorHttpReset] rebuilds the HTTP client on Tor state changes, so this orchestrator is commonMain.
 */
class ArtiTorManager(
    private val dataDirProvider: TorDataDirProvider,
    private val torPreferenceManager: TorPreferenceManager,
    private val httpReset: TorHttpReset,
    dispatchers: AppDispatchers = AppDispatchers(),
) {
    enum class TorState {
        OFF,
        STARTING,
        BOOTSTRAPPING,
        RUNNING,
        STOPPING,
        ERROR
    }

    data class TorStatus(
        val mode: TorMode = TorMode.OFF,
        val running: Boolean = false,
        val bootstrapPercent: Int = 0,
        val lastLogLine: String = "",
        val state: TorState = TorState.OFF
    )

    companion object {
        private const val TAG = "ArtiTorManager"
        private const val DEFAULT_SOCKS_PORT = TorConstants.DEFAULT_SOCKS_PORT
        private const val RESTART_DELAY_MS = TorConstants.RESTART_DELAY_MS
        private const val MAX_RETRY_ATTEMPTS = TorConstants.MAX_RETRY_ATTEMPTS

        // Bound a single bootstrap attempt. Arti retries directory fetches internally, so we only
        // step in (via scheduleRetry) if it never reaches RUNNING — no aggressive periodic restarts.
        private const val BOOTSTRAP_TIMEOUT_MS = 180_000L
    }

    private val appScope = CoroutineScope(dispatchers.io + SupervisorJob())

    /** One reusable client; the native side supports stop -> start cycles. */
    private val client = ArtiTorClient()

    private val initLock = Lock()

    @Volatile
    private var initialized = false
    @Volatile
    private var socksAddr: SocksProxyAddress? = null
    @Volatile
    private var desiredMode: TorMode = TorMode.OFF
    @Volatile
    private var currentSocksPort: Int = DEFAULT_SOCKS_PORT
    @Volatile
    private var retryAttempts = 0

    private val applyMutex = Mutex()
    private var retryJob: Job? = null
    private var startJob: Job? = null

    private enum class LifecycleState { STOPPED, STARTING, RUNNING, STOPPING }

    @Volatile
    private var lifecycleState: LifecycleState = LifecycleState.STOPPED

    private val _statusFlow = MutableStateFlow(TorStatus())
    val statusFlow: StateFlow<TorStatus> = _statusFlow.asStateFlow()

    fun isProxyEnabled(): Boolean {
        val s = _statusFlow.value
        return s.mode != TorMode.OFF && s.running && s.bootstrapPercent >= 100 &&
                socksAddr != null && s.state == TorState.RUNNING
    }

    fun init() {
        if (initialized) return
        initLock.withLock {
            if (initialized) return
            initialized = true

            // First-class status: map the library's bootstrap/state signal onto our flow.
            appScope.launch {
                client.status.collect { s ->
                    s.socksPort?.let { socksAddr = SocksProxyAddress("127.0.0.1", it) }
                    _statusFlow.update { cur ->
                        cur.copy(
                            bootstrapPercent = s.bootstrapPercent,
                            state = s.state.toManagerState(),
                            running = s.state == LibTorState.BOOTSTRAPPING || s.state == LibTorState.RUNNING,
                        )
                    }
                }
            }
            // Optional debug log line (kept only for UI/diagnostics, never parsed for state).
            appScope.launch {
                client.logs.collect { line ->
                    Log.i(TAG, "arti: $line")
                    _statusFlow.update { it.copy(lastLogLine = line) }
                }
            }

            val savedMode = torPreferenceManager.get()
            if (savedMode == TorMode.ON) {
                if (currentSocksPort < DEFAULT_SOCKS_PORT) currentSocksPort = DEFAULT_SOCKS_PORT
                desiredMode = savedMode
                socksAddr = SocksProxyAddress("127.0.0.1", currentSocksPort)
                runCatching { httpReset.reset() }
            }
            appScope.launch { applyMode(savedMode) }
            appScope.launch {
                torPreferenceManager.modeFlow.collect { mode -> applyMode(mode) }
            }
        }
    }

    fun currentSocksAddress(): SocksProxyAddress? = socksAddr

    suspend fun applyMode(mode: TorMode) {
        applyMutex.withCoroutineLock {
            try {
                desiredMode = mode
                val s = _statusFlow.value
                if (mode == s.mode && mode != TorMode.OFF &&
                    (lifecycleState == LifecycleState.STARTING || lifecycleState == LifecycleState.RUNNING)
                ) {
                    Log.i(TAG, "applyMode: already in progress/running mode=$mode; skip")
                    return
                }
                when (mode) {
                    TorMode.OFF -> {
                        Log.i(TAG, "applyMode: OFF -> stopping tor")
                        lifecycleState = LifecycleState.STOPPING
                        _statusFlow.update {
                            it.copy(mode = TorMode.OFF, running = false, bootstrapPercent = 0, state = TorState.STOPPING)
                        }
                        stopArti()
                        socksAddr = null
                        _statusFlow.update {
                            it.copy(mode = TorMode.OFF, running = false, bootstrapPercent = 0, state = TorState.OFF)
                        }
                        currentSocksPort = DEFAULT_SOCKS_PORT
                        lifecycleState = LifecycleState.STOPPED
                        resetNetworkConnections()
                    }

                    TorMode.ON -> {
                        Log.i(TAG, "applyMode: ON -> starting arti")
                        if (currentSocksPort < DEFAULT_SOCKS_PORT) currentSocksPort = DEFAULT_SOCKS_PORT
                        lifecycleState = LifecycleState.STARTING
                        _statusFlow.update {
                            it.copy(mode = TorMode.ON, running = false, bootstrapPercent = 0, state = TorState.STARTING)
                        }
                        // Set the SOCKS address eagerly so no direct connection slips out before bootstrap.
                        socksAddr = SocksProxyAddress("127.0.0.1", currentSocksPort)
                        resetNetworkConnections()
                        startArti()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply Arti mode: ${e.message}")
            }
        }
    }

    /** Launches a bootstrap+SOCKS attempt; [ArtiTorClient.start] suspends until RUNNING or ERROR. */
    private fun startArti() {
        startJob?.cancel()
        val port = currentSocksPort
        startJob = appScope.launch {
            // Reuse one client across cycles; make sure any previous run is torn down first.
            client.stop()
            val dataDir = dataDirProvider.artiDataDir()
            Log.i(TAG, "Starting Arti on port $port (data=$dataDir)…")

            // Bound the bootstrap. Arti retries directory fetches internally, so we don't restart on
            // short stalls — only if it never reaches RUNNING within the timeout.
            val result = withTimeoutOrNull(BOOTSTRAP_TIMEOUT_MS.milliseconds) {
                client.start(ArtiConfig(dataDir = dataDir, socksPort = port))
            }
            if (desiredMode != TorMode.ON) return@launch

            when {
                result == null -> {
                    Log.w(TAG, "Bootstrap did not finish within ${BOOTSTRAP_TIMEOUT_MS}ms")
                    runCatching { client.stop() }
                    _statusFlow.update { it.copy(state = TorState.ERROR, running = false) }
                    scheduleRetry()
                }

                result.isSuccess -> {
                    lifecycleState = LifecycleState.RUNNING
                    retryAttempts = 0
                    socksAddr = SocksProxyAddress("127.0.0.1", port)
                    Log.i(TAG, "Tor RUNNING: proxy at $socksAddr")
                    resetNetworkConnections()
                }

                else -> {
                    Log.e(TAG, "Arti start failed on port $port: ${result.exceptionOrNull()?.message}")
                    _statusFlow.update { it.copy(state = TorState.ERROR, running = false) }
                    scheduleRetry()
                }
            }
        }
    }

    private fun stopArti() {
        startJob?.cancel()
        stopRetryMonitoring()
        runCatching { client.stop() }
    }

    private suspend fun restartArti() {
        Log.i(TAG, "Restarting Arti…")
        stopArti()
        delay(RESTART_DELAY_MS)
        if (desiredMode == TorMode.ON) startArti()
    }

    private fun scheduleRetry() {
        retryJob?.cancel()
        if (retryAttempts < MAX_RETRY_ATTEMPTS) {
            retryAttempts++
            val delayMs = (1000L * (1 shl retryAttempts)).coerceAtMost(30000L)
            Log.w(TAG, "Scheduling Arti retry $retryAttempts in ${delayMs}ms")
            retryJob = appScope.launch {
                delay(delayMs)
                if (_statusFlow.value.mode == TorMode.ON) restartArti()
            }
        } else {
            Log.e(TAG, "Max retry attempts reached, giving up on Arti connection")
        }
    }

    private fun stopRetryMonitoring() {
        retryJob?.cancel()
        retryJob = null
    }

    /**
     * Invoked after Tor network state changes so dependents (e.g. the Nostr relay manager) can
     * reconnect over the new circuit. Wired from the app module so this Tor substrate stays unaware
     * of the relay layer.
     */
    var onConnectionsReset: (() -> Unit)? = null

    private fun resetNetworkConnections() {
        runCatching { httpReset.reset() }
        runCatching { onConnectionsReset?.invoke() }
    }

    fun isTorAvailable(): Boolean = true

    private fun LibTorState.toManagerState(): TorState = when (this) {
        LibTorState.OFF -> TorState.OFF
        LibTorState.STARTING -> TorState.STARTING
        LibTorState.BOOTSTRAPPING -> TorState.BOOTSTRAPPING
        LibTorState.RUNNING -> TorState.RUNNING
        LibTorState.STOPPING -> TorState.STOPPING
        LibTorState.ERROR -> TorState.ERROR
    }
}
