package com.app.transport.mesh

import com.app.transport.protocol.BitchatPacket
import com.app.transport.protocol.MessageType
import com.app.transport.protocol.peerIdToRoutingBytes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BleIngressLinkRegistryConcurrencyTest {

    @Test
    fun concurrentWritersNeverExceedHardCap() {
        val registry = BleIngressLinkRegistry()
        val workers = 8
        val recordsPerWorker = 1_000
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val done = CountDownLatch(workers)
        val pool = Executors.newFixedThreadPool(workers)

        repeat(workers) { worker ->
            pool.execute {
                ready.countDown()
                start.await()
                repeat(recordsPerWorker) { offset ->
                    val index = worker * recordsPerWorker + offset
                    registry.recordIfNew(
                        packet = BitchatPacket(
                            version = 1u,
                            type = MessageType.MESSAGE.value,
                            senderID = peerIdToRoutingBytes("1111111111111111"),
                            recipientID = null,
                            timestamp = (1_700_000_000_000L + index).toULong(),
                            payload = byteArrayOf((index % 251).toByte()),
                            signature = null,
                            ttl = 7u,
                        ),
                        link = BleIngressLinkId.Peripheral("link-$worker"),
                        peerID = "peer-$worker",
                        nowMs = index.toLong(),
                        lifetimeMs = Long.MAX_VALUE,
                    )
                }
                done.countDown()
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(20, TimeUnit.SECONDS))
        pool.shutdownNow()

        assertEquals(4096, registry.debugRecordCount())
    }
}
