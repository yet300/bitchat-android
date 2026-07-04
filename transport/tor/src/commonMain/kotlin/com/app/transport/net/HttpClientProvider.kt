package com.app.transport.net

import co.touchlab.stately.concurrency.Lock
import co.touchlab.stately.concurrency.withLock
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets

/**
 * Centralized ktor [HttpClient] provider so all network traffic honors Tor settings.
 *
 * Platform-free: the SOCKS proxy is configured through ktor's multiplatform [ProxyBuilder] and
 * timeouts through the [HttpTimeout] plugin, so only the engine is injected per platform
 * ([HttpClientEngineFactory] — OkHttp on Android, Darwin on iOS later). Both clients are rebuilt
 * on [reset] when the Tor circuit changes.
 *
 * Graph-owned: the SOCKS source is injected (formerly a mutable static wired by
 * ArtiTorManager.init()).
 */
class HttpClientProvider(
    private val socksAddressSource: SocksAddressSource,
    private val engineFactory: HttpClientEngineFactory<*>,
) : WebSocketClientProvider {
    private val lock = Lock()
    private var httpClient: HttpClient? = null
    private var wsClient: HttpClient? = null

    fun reset() {
        val (oldHttp, oldWs) = lock.withLock {
            val h = httpClient
            val w = wsClient
            httpClient = null
            wsClient = null
            h to w
        }
        oldHttp?.close()
        oldWs?.close()
    }

    fun httpClient(): HttpClient = lock.withLock {
        httpClient ?: buildHttpClient().also { httpClient = it }
    }

    override fun webSocketClient(): HttpClient = lock.withLock {
        wsClient ?: buildWebSocketClient().also { wsClient = it }
    }

    private fun buildHttpClient(): HttpClient = HttpClient(engineFactory) {
        expectSuccess = false
        engine { applyTorProxy() }
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
    }

    private fun buildWebSocketClient(): HttpClient = HttpClient(engineFactory) {
        expectSuccess = false
        install(WebSockets)
        engine { applyTorProxy() }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            // socketTimeoutMillis left unset (infinite) to keep long-lived relay sockets open.
        }
    }

    // If a SOCKS address is defined, always use it. ArtiTorManager sets it as soon as Tor mode is
    // ON, even before bootstrap, to prevent any direct connections from leaking.
    private fun HttpClientEngineConfig.applyTorProxy() {
        val socks = socksAddressSource.current()
        if (socks != null) {
            proxy = ProxyBuilder.socks(socks.host, socks.port)
        }
    }
}
