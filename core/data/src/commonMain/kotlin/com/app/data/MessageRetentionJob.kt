@file:OptIn(ExperimentalTime::class)

package com.app.data

import com.app.common.utils.Log
import com.app.database.dao.MessageDao
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.ExperimentalTime

/**
 * Periodic retention sweep for the `message` table (audit §3.5). Without it the timelines are capped
 * in memory (AppStateStore.TIMELINE_CAP = 500 per conversation) but the on-disk table grows forever.
 *
 * Policy (owner-delegated — see the phase report for the reasoning):
 *  - **Per-conversation cap** mirrors the in-memory TIMELINE_CAP: keep the newest [perConversationCap]
 *    messages per conversation, so the DB never holds more than the UI can ever show.
 *  - **Age cutoff** drops anything older than [maxAgeMillis] regardless of count — this repo's whole
 *    premise is ephemeral messaging (native Android is in-memory only; iOS keeps no permanent DB), so
 *    persistence here is already an extension and an upper age bound keeps it honest.
 *
 * File size: deleted rows leave free pages that SQLite reuses for later inserts, so the file plateaus
 * at the steady-state working set rather than shrinking. We deliberately do NOT VACUUM (it rewrites the
 * whole encrypted DB under an exclusive lock) nor set `auto_vacuum` (it must be chosen at DB creation,
 * which the SQLCipher driver does not expose a hook for, and toggling it on an existing DB needs a full
 * VACUUM anyway). With a bounded row count, free-page reuse is sufficient.
 *
 * Started explicitly ([start]) from the foreground service so unit tests can drive [sweep] directly
 * without a self-scheduling delay loop making virtual-time drains non-terminating.
 */
@SingleIn(AppScope::class)
@Inject
class MessageRetentionJob(
    private val messageDao: MessageDao,
    private val scope: CoroutineScope,
) {

    /** Newest messages kept per conversation. Mirrors AppStateStore.TIMELINE_CAP. */
    var perConversationCap: Long = DEFAULT_PER_CONVERSATION_CAP

    /** Messages older than this are dropped regardless of count; 0 disables the age sweep. */
    var maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS

    /** Interval between sweeps once [start] is running. */
    var intervalMillis: Long = DEFAULT_INTERVAL_MILLIS

    private var job: Job? = null

    /** Launch the periodic sweep loop (idempotent). Runs one sweep immediately, then every interval. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                sweep()
                delay(intervalMillis.milliseconds)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Run one retention sweep. Public/suspend so tests exercise it without the scheduling loop. */
    suspend fun sweep() {
        try {
            if (maxAgeMillis > 0) {
                val cutoff = Clock.System.now().toEpochMilliseconds() - maxAgeMillis
                messageDao.deleteOlderThan(cutoff)
            }
            if (perConversationCap > 0) {
                messageDao.enforcePerConversationCap(perConversationCap)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "retention sweep failed: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "MessageRetentionJob"

        const val DEFAULT_PER_CONVERSATION_CAP = 500L
        const val DEFAULT_MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000 // 30 days
        const val DEFAULT_INTERVAL_MILLIS = 6L * 60 * 60 * 1000       // 6 hours
    }
}
