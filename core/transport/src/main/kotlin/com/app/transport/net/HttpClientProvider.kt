package com.app.transport.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.plugins.websocket.WebSockets
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Centralized ktor [HttpClient] provider so all network traffic honors Tor settings.
 *
 * Application code uses the multiplatform ktor API; the OkHttp engine stays an implementation
 * detail of this module. OkHttp is the Android engine that supports both WebSockets (Nostr relays)
 * and a SOCKS proxy (Arti/Tor), which is why it backs both clients here.
 */
object HttpClientProvider {
    private val httpClientRef = AtomicReference<HttpClient?>(null)
    private val wsClientRef = AtomicReference<HttpClient?>(null)

    /**
     * Set by ArtiTorManager.init() so this object never calls getInstance() directly.
     * Retires when HttpClientProvider is graph-owned (Phase C).
     */
    var currentSocksProvider: (() -> java.net.InetSocketAddress?)? = null

    fun reset() {
        httpClientRef.getAndSet(null)?.close()
        wsClientRef.getAndSet(null)?.close()
    }

    fun httpClient(): HttpClient {
        httpClientRef.get()?.let { return it }
        val created = HttpClient(OkHttp) {
            expectSuccess = false
            engine {
                applyTorProxy()
                config {
                    callTimeout(15, TimeUnit.SECONDS)
                    connectTimeout(10, TimeUnit.SECONDS)
                    readTimeout(15, TimeUnit.SECONDS)
                }
            }
        }
        return cacheOrClose(httpClientRef, created)
    }

    fun webSocketClient(): HttpClient {
        wsClientRef.get()?.let { return it }
        val created = HttpClient(OkHttp) {
            expectSuccess = false
            install(WebSockets)
            engine {
                applyTorProxy()
                config {
                    connectTimeout(10, TimeUnit.SECONDS)
                    readTimeout(0, TimeUnit.SECONDS) // keep long-lived relay sockets open
                    writeTimeout(10, TimeUnit.SECONDS)
                }
            }
        }
        return cacheOrClose(wsClientRef, created)
    }

    // If a SOCKS address is defined, always use it. ArtiTorManager sets currentSocksProvider as
    // soon as Tor mode is ON, even before bootstrap, to prevent any direct connections from occurring.
    private fun OkHttpConfig.applyTorProxy() {
        val socks = currentSocksProvider?.invoke()
        if (socks != null) {
            proxy = Proxy(Proxy.Type.SOCKS, socks)
        }
    }

    private fun cacheOrClose(ref: AtomicReference<HttpClient?>, created: HttpClient): HttpClient {
        return if (ref.compareAndSet(null, created)) {
            created
        } else {
            created.close()
            ref.get() ?: created
        }
    }
}
