package com.app.transport.net

/**
 * A SOCKS proxy endpoint (always loopback for the Arti/Tor client). commonMain replacement for
 * java.net.InetSocketAddress so the Tor manager and the WebSocket/HTTP seam stay platform-free;
 * the Android engine maps this to an InetSocketAddress when configuring OkHttp.
 */
data class SocksProxyAddress(val host: String, val port: Int)

/**
 * Live SOCKS proxy address for outbound traffic, or null for direct connections.
 *
 * Implemented over [com.app.transport.net.ArtiTorManager] (bound with a Lazy handle to break the
 * construction cycle: the manager resets the HTTP client on Tor state changes). The concrete HTTP
 * engine consults this per connection.
 */
fun interface SocksAddressSource {
    fun current(): SocksProxyAddress?
}
