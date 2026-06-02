@file:OptIn(ExperimentalTime::class)

package com.bitchat.android.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

class DeliveryStatusPolicyTest {

    private val t = Instant.fromEpochMilliseconds(1_000L)

    @Test fun `upgrade from delivered to read`() {
        val result = DeliveryStatusPolicy.merge(DeliveryStatus.Delivered("a", t), DeliveryStatus.Read("a", t))
        assertEquals(DeliveryStatus.Read("a", t), result)
    }

    @Test fun `never downgrade read to delivered`() {
        val result = DeliveryStatusPolicy.merge(DeliveryStatus.Read("a", t), DeliveryStatus.Delivered("a", t))
        assertEquals(DeliveryStatus.Read("a", t), result)
    }

    @Test fun `null old accepts new`() {
        assertEquals(DeliveryStatus.Sending, DeliveryStatusPolicy.merge(null, DeliveryStatus.Sending))
    }

    @Test fun `sent is not downgraded by sending`() {
        assertEquals(DeliveryStatus.Sent, DeliveryStatusPolicy.merge(DeliveryStatus.Sent, DeliveryStatus.Sending))
    }

    @Test fun `equal priority takes new`() {
        val newer = DeliveryStatus.Delivered("b", t)
        assertEquals(newer, DeliveryStatusPolicy.merge(DeliveryStatus.Delivered("a", t), newer))
    }
}
