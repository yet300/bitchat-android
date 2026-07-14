package com.app.transport.mesh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BleRedundantLinkPolicyTest {

    private fun link(
        uuid: String,
        peerID: String? = "peerA",
        connected: Boolean = true,
        hasCharacteristic: Boolean = true,
    ) = BleRedundantLinkPolicy.PeripheralLink(
        uuid = uuid,
        peerID = peerID,
        isConnected = connected,
        hasCharacteristic = hasCharacteristic,
    )

    @Test
    fun singleBoundLink_returnsNullKeep() {
        val links = listOf(link("u1"))
        assertNull(
            BleRedundantLinkPolicy.keptPeripheralUUID(
                ingressPeripheralUUID = "u1",
                mostRecentlyBoundUUID = "u1",
                links = links,
                peerID = "peerA",
            ),
        )
    }

    @Test
    fun prefersIngressWhenWritable() {
        val links = listOf(link("u1"), link("u2"))
        assertEquals(
            "u1",
            BleRedundantLinkPolicy.keptPeripheralUUID(
                ingressPeripheralUUID = "u1",
                mostRecentlyBoundUUID = "u2",
                links = links,
                peerID = "peerA",
            ),
        )
    }

    @Test
    fun fallsBackToMostRecentlyBound() {
        val links = listOf(link("u1"), link("u2"))
        assertEquals(
            "u2",
            BleRedundantLinkPolicy.keptPeripheralUUID(
                ingressPeripheralUUID = null,
                mostRecentlyBoundUUID = "u2",
                links = links,
                peerID = "peerA",
            ),
        )
    }

    @Test
    fun prefersWritableOverCharacteristicLess() {
        val links = listOf(
            link("u1", hasCharacteristic = false),
            link("u2", hasCharacteristic = true),
        )
        // Ingress u1 is not writable while a writable candidate exists → not kept.
        assertEquals(
            "u2",
            BleRedundantLinkPolicy.keptPeripheralUUID(
                ingressPeripheralUUID = "u1",
                mostRecentlyBoundUUID = "u2",
                links = links,
                peerID = "peerA",
            ),
        )
    }

    @Test
    fun whenOnlyNonWritableExist_allowsNonWritableKeep() {
        val links = listOf(
            link("u1", hasCharacteristic = false),
            link("u2", hasCharacteristic = false),
        )
        assertEquals(
            "u1",
            BleRedundantLinkPolicy.keptPeripheralUUID(
                ingressPeripheralUUID = "u1",
                mostRecentlyBoundUUID = "u2",
                links = links,
                peerID = "peerA",
            ),
        )
    }

    @Test
    fun noViableAnchor_returnsNull() {
        val links = listOf(link("u1"), link("u2"))
        assertNull(
            BleRedundantLinkPolicy.keptPeripheralUUID(
                ingressPeripheralUUID = "other",
                mostRecentlyBoundUUID = "also-other",
                links = links,
                peerID = "peerA",
            ),
        )
    }

    @Test
    fun ignoresOtherPeersAndDisconnected() {
        val links = listOf(
            link("u1", peerID = "peerA"),
            link("u2", peerID = "peerB"),
            link("u3", peerID = "peerA", connected = false),
        )
        // Only one connected peerA link → nothing to consolidate.
        assertNull(
            BleRedundantLinkPolicy.keptPeripheralUUID(
                ingressPeripheralUUID = "u1",
                mostRecentlyBoundUUID = "u1",
                links = links,
                peerID = "peerA",
            ),
        )
    }

    @Test
    fun retireListExcludesKept() {
        val links = listOf(link("u1"), link("u2"), link("u3"))
        val retiring = BleRedundantLinkPolicy.peripheralUUIDsToRetire(
            links = links,
            peerID = "peerA",
            keeping = "u2",
        )
        assertEquals(setOf("u1", "u3"), retiring.toSet())
    }

    @Test
    fun retireListEmptyWhenOnlyKeptMatches() {
        val links = listOf(link("u1"))
        assertTrue(
            BleRedundantLinkPolicy.peripheralUUIDsToRetire(
                links = links,
                peerID = "peerA",
                keeping = "u1",
            ).isEmpty(),
        )
    }
}
