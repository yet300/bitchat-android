package com.app.transport.net

import io.ktor.client.HttpClient

/**
 * Supplies a ktor [HttpClient] configured for long-lived WebSocket relay connections.
 *
 * commonMain seam so the Nostr relay logic (NostrRelayManager) stays platform-free: the concrete
 * client — OkHttp engine + Tor SOCKS proxy on Android — is built by androidMain's HttpClientProvider,
 * which implements this interface. An iOS implementation (Darwin engine) is a later follow-up.
 *
 * The client is fetched per use (not cached by the caller) because the provider may rebuild it when
 * Tor state changes.
 */
fun interface WebSocketClientProvider {
    fun webSocketClient(): HttpClient
}
