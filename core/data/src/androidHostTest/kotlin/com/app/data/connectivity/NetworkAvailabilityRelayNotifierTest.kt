package com.app.data.connectivity

import com.app.domain.model.TransportState
import com.app.transport.nostr.NostrRelayManager
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

class NetworkAvailabilityRelayNotifierTest {
    private val relayManager = mock<NostrRelayManager>()
    private val notifier = NetworkAvailabilityRelayNotifier(relayManager)

    @Test
    fun `internet restoration immediately re-probes exhausted relays once`() {
        notifier.onInternetState(TransportState.OFF)
        notifier.onInternetState(TransportState.ON)
        notifier.onInternetState(TransportState.ON)

        verify(relayManager).onNetworkAvailable()
    }

    @Test
    fun `initial online snapshot does not pretend the network returned`() {
        notifier.onInternetState(TransportState.ON)

        verify(relayManager, never()).onNetworkAvailable()
    }
}
