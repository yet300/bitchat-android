package com.app.data.connectivity

import com.app.domain.model.TransportState
import com.app.transport.nostr.NostrRelayManager

/** Turns Android's polled internet state into a single transport-layer recovery edge. */
internal class NetworkAvailabilityRelayNotifier(
    private val relayManager: NostrRelayManager,
) {
    private var wasInternetAvailable: Boolean? = null

    fun onInternetState(state: TransportState) {
        val isInternetAvailable = state == TransportState.ON
        if (wasInternetAvailable == false && isInternetAvailable) {
            relayManager.onNetworkAvailable()
        }
        wasInternetAvailable = isInternetAvailable
    }
}
