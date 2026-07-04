package com.app.transport.net

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp

/**
 * The Android ktor engine for [HttpClientProvider]. OkHttp supports both WebSockets (Nostr relays)
 * and a SOCKS proxy (Arti/Tor). Exposed as a function so the DI module in :shared can bind it
 * without depending on ktor-client-okhttp directly (it stays an androidMain implementation detail
 * of :core:transport).
 */
fun androidHttpClientEngineFactory(): HttpClientEngineFactory<*> = OkHttp
