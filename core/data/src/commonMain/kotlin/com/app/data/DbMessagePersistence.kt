@file:OptIn(ExperimentalTime::class)

package com.app.data

import com.app.common.serialization.JsonConfig
import com.app.common.utils.Log
import com.app.database.Message
import com.app.database.dao.MessageDao
import com.app.transport.model.BitchatMessage
import com.app.transport.model.DeliveryStatus
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.ExperimentalTime

/**
 * DB-backed [MessagePersistence]: mirrors the timelines into the encrypted `message` table via
 * [MessageDao]. The full wire message is stored as JSON ([Message.wire_json]) for lossless
 * reconstruction; the other columns are its searchable projection.
 *
 * All mutations funnel through a single worker coroutine fed by an unbounded [Channel]. This buys two
 * properties the old `scope.launch { … }`-per-call design lacked (audit §3.4):
 *  - **Ordering.** The channel is FIFO, so an [updateStatus] enqueued after its [persist] always runs
 *    after it. The previous code raced: `updateStatus` read `byId` on a fresh coroutine that could win
 *    against the not-yet-committed insert and silently drop the status.
 *  - **Batching.** Under a message storm the worker coalesces everything that piled up within
 *    [batchWindowMs] into one SQLCipher transaction (one fsync) instead of thousands of independent
 *    transactions each paying an fsync, and it no longer spawns an unbounded swarm of coroutines.
 */
@SingleIn(AppScope::class)
@Inject
class DbMessagePersistence(
    private val messageDao: MessageDao,
    private val scope: CoroutineScope,
) : MessagePersistence {

    private sealed interface Command {
        data class Persist(val conversationKey: String, val message: BitchatMessage) : Command
        data class UpdateStatus(val messageId: String, val status: DeliveryStatus) : Command
        data class Delete(val messageId: String) : Command
        data class Clear(val conversationKey: String?) : Command
    }

    // Unbounded so the non-suspending mesh sink never blocks or drops; commands are tiny and the
    // worker drains them in batches faster than they realistically arrive.
    private val commands = Channel<Command>(Channel.UNLIMITED)

    /** Window the worker waits after the first queued command to let a burst accumulate. */
    var batchWindowMs: Long = DEFAULT_BATCH_WINDOW_MS

    /** Upper bound on commands folded into one transaction (keeps a single txn from starving reads). */
    var maxBatchSize: Int = DEFAULT_MAX_BATCH_SIZE

    /** Test-only observability: invoked once per flushed transaction with the batch size. */
    internal var onBatchFlushed: ((Int) -> Unit)? = null

    init {
        scope.launch { runWorker() }
    }

    override fun persist(conversationKey: String, message: BitchatMessage) {
        commands.trySend(Command.Persist(conversationKey, message))
    }

    override fun updateStatus(messageId: String, status: DeliveryStatus) {
        commands.trySend(Command.UpdateStatus(messageId, status))
    }

    override fun delete(messageId: String) {
        commands.trySend(Command.Delete(messageId))
    }

    override fun clear(conversationKey: String?) {
        commands.trySend(Command.Clear(conversationKey))
    }

    override suspend fun load(perConversationLimit: Long): Map<String, List<BitchatMessage>> =
        messageDao.conversationIds().associateWith { id ->
            messageDao.recentByConversation(id, perConversationLimit).mapNotNull { it.wire_json?.let(::decode) }
        }

    private suspend fun runWorker() {
        while (true) {
            val first = try {
                commands.receive()
            } catch (_: CancellationException) {
                throw CancellationException("persistence worker cancelled")
            }
            val batch = ArrayList<Command>(maxBatchSize)
            batch.add(first)
            if (batchWindowMs > 0) delay(batchWindowMs)
            while (batch.size < maxBatchSize) {
                val next = commands.tryReceive().getOrNull() ?: break
                batch.add(next)
            }
            try {
                flush(batch)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to flush ${batch.size} persistence commands: ${e.message}")
            }
            onBatchFlushed?.invoke(batch.size)
        }
    }

    private suspend fun flush(batch: List<Command>) = messageDao.runInTransaction { tx ->
        for (command in batch) {
            when (command) {
                is Command.Persist ->
                    tx.upsert(command.message.toEntity(command.conversationKey))

                is Command.UpdateStatus -> {
                    val row = tx.selectById(command.messageId)
                    val wire = row?.wire_json?.let(::decode)
                    if (row != null && wire != null) {
                        tx.upsert(wire.copy(deliveryStatus = command.status).toEntity(row.conversation_id))
                    }
                }

                is Command.Delete -> tx.deleteById(command.messageId)

                is Command.Clear ->
                    if (command.conversationKey == null) tx.deleteAll()
                    else tx.deleteByConversation(command.conversationKey)
            }
        }
    }

    private fun decode(json: String): BitchatMessage? =
        runCatching { JsonConfig.json.decodeFromString(BitchatMessage.serializer(), json) }.getOrNull()

    private fun BitchatMessage.toEntity(conversationKey: String): Message = Message(
        id = id,
        conversation_id = conversationKey,
        sender_peer_id = senderPeerID,
        sender_name = sender,
        content = content,
        timestamp = timestamp.toEpochMilliseconds(),
        type = type.name,
        is_mine = 0L,
        is_relay = if (isRelay) 1L else 0L,
        mentions = null,
        delivery_status = null,
        attachment_path = null,
        attachment_type = null,
        pow_difficulty = powDifficulty?.toLong(),
        channel = channel,
        wire_json = JsonConfig.json.encodeToString(BitchatMessage.serializer(), this),
    )

    private companion object {
        const val TAG = "DbMessagePersistence"

        // 25ms coalesces a burst without adding user-visible latency to the timeline mirror.
        const val DEFAULT_BATCH_WINDOW_MS = 25L
        const val DEFAULT_MAX_BATCH_SIZE = 500
    }
}
