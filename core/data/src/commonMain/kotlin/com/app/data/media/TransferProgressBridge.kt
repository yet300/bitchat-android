package com.app.data.media

import co.touchlab.stately.collections.ConcurrentMutableMap
import com.app.common.AppDispatchers
import com.app.data.AppStateStore
import com.app.transport.mesh.TransferProgressManager
import com.app.transport.model.DeliveryStatus
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Reflects mesh file-transfer progress (keyed by transferId) onto the corresponding timeline
 * message's delivery status, so an outgoing attachment's progress bar advances and settles at 100%.
 *
 * The transferId↔messageId link is established by [AttachmentSender] when it sends (the mesh derives
 * the same sha256(payload) transferId), since the progress events carry only the transferId.
 */
@SingleIn(AppScope::class)
@Inject
class TransferProgressBridge(
    progress: TransferProgressManager,
    private val appStateStore: AppStateStore,
    dispatchers: AppDispatchers,
) {

    private val scope = CoroutineScope(dispatchers.io + SupervisorJob())
    private val messageIdByTransfer = ConcurrentMutableMap<String, String>()

    init {
        progress.events
            .onEach { event ->
                val messageId = messageIdByTransfer[event.transferId] ?: return@onEach
                appStateStore.updateMessageStatus(
                    messageId,
                    DeliveryStatus.PartiallyDelivered(reached = event.sent, total = event.total),
                )
                if (event.completed) messageIdByTransfer.remove(event.transferId)
            }
            .launchIn(scope)
    }

    fun track(transferId: String, messageId: String) {
        messageIdByTransfer[transferId] = messageId
    }
}
