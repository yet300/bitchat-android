package com.app.transport.net

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.plugins.websocket.WebSockets
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Live SOCKS proxy address for outbound traffic, or null for direct connections.
 * Implemented over ArtiTorManager (bound with a Lazy handle to break the construction
 * cycle: the manager itself calls [HttpClientProvider.reset] on Tor state changes).
 */
fun interface SocksAddressSource {
    fun current(): InetSocketAddress?
}

/**
 * Centralized ktor [HttpClient] provider so all network traffic honors Tor settings.
 *
 * Application code uses the multiplatform ktor API; the OkHttp engine stays an implementation
 * detail of this module. OkHttp is the Android engine that supports both WebSockets (Nostr relays)
 * and a SOCKS proxy (Arti/Tor), which is why it backs both clients here.
 *
 * Graph-owned: the SOCKS source is injected (formerly a mutable static wired by
 * ArtiTorManager.init()).
 */
@SingleIn(AppScope::class)
@Inject
class HttpClientProvider(private val socksAddressSource: SocksAddressSource) : WebSocketClientProvider {
    private val httpClientRef = AtomicReference<HttpClient?>(null)
    private val wsClientRef = AtomicReference<HttpClient?>(null)

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

    override fun webSocketClient(): HttpClient {
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
        val socks = socksAddressSource.current()
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
