package com.app.domain.model

/**
 * How a conversation can be reached *right now* — a live overlay on the persistent conversation
 * list (and the chat header), not a list-membership criterion. Derived from peer/contact state by
 * [ResolveReachabilityUseCase][com.app.domain.usecase.ResolveReachabilityUseCase].
 */
enum class Reachability {

    /** Reachable over the local mesh now (BLE / Wi-Fi Aware), direct or relayed; E2E for DMs. */
    NEARBY,

    /** Reachable over the internet via Nostr relays (a mutual favorite, or a geo/relay room). */
    INTERNET,

    /** No current route — composing is allowed, delivery waits until a route appears. */
    OFFLINE,
}
