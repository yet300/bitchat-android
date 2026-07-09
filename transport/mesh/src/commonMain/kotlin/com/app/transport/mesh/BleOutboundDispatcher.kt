package com.app.transport.mesh

import co.touchlab.stately.collections.ConcurrentMutableMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the per-link [BleLinkOutboundBuffer]s and the single backup-drain coroutine. The platform
 * radio adapters (Android [BluetoothPacketBroadcaster], Apple [CoreBluetoothConnectionManager]) route
 * their frame writes through [submit] and forward their readiness callbacks
 * (`onNotificationSent` / `peripheralManagerIsReady` / `onCharacteristicWrite` / `peripheralIsReady`)
 * to [onReady]. This is the commonMain home of the four radio write paths' back-pressure handling —
 * the platform keeps only the raw chunk write and the MTU.
 *
 * Draining is driven primarily by the platform readiness signal via [onReady]; the backup coroutine
 * is a safety net for a missed signal. It sleeps until [wake] fires (on any newly-queued work) and
 * then re-drains every [BleRadioConfig.notifyRetryDelayMs] until all links are empty — never a busy
 * loop while idle.
 */
class BleOutboundDispatcher(
    private val scope: CoroutineScope,
    private val config: BleRadioConfig = BleRadioConfig(),
    private val onOutboundDropped: () -> Unit = {},
) {
    private val buffers = ConcurrentMutableMap<String, BleLinkOutboundBuffer>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ignored in wake) {
                while (isActive && anyPending()) {
                    buffers.values.forEach { it.drain() }
                    delay(config.notifyRetryDelayMs)
                }
            }
        }
    }

    /**
     * Chunk [frame] to [maxChunkBytes] and write it to [linkAddress], synchronously while the link is
     * idle. Returns the value the platform's `writeToNeighbor` should return: true = accepted (sent or
     * queued for retry), false = link gone.
     */
    fun submit(
        linkAddress: String,
        frame: ByteArray,
        maxChunkBytes: Int,
        writer: BleChunkWriter,
        priority: BleOutboundPriority,
        capBytes: Int,
    ): Boolean {
        val buffer = buffers.getOrPut(linkAddress) { BleLinkOutboundBuffer(onOutboundDropped) }
        val accepted = buffer.submit(frame, maxChunkBytes, writer, priority, capBytes)
        if (!buffer.isEmpty) wake.trySend(Unit)
        return accepted
    }

    /** A link's readiness callback fired — drain its pending chunks now. */
    fun onReady(linkAddress: String) {
        val buffer = buffers[linkAddress] ?: return
        if (buffer.drain()) wake.trySend(Unit)
    }

    /** Link disconnected: drop its buffer (also fixes the old ever-growing emissionLocks map leak). */
    fun dropLink(linkAddress: String) {
        buffers.remove(linkAddress)?.clear()
    }

    fun shutdown() {
        wake.close()
        buffers.values.forEach { it.clear() }
        buffers.clear()
    }

    private fun anyPending(): Boolean = buffers.values.any { !it.isEmpty }
}
