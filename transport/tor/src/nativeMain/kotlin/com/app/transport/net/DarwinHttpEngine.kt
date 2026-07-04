package com.app.transport.net

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin


fun darwinHttpClientEngineFactory(): HttpClientEngineFactory<*> = Darwin
