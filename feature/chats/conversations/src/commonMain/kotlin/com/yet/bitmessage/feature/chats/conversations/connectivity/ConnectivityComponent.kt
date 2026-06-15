package com.yet.bitmessage.feature.chats.conversations.connectivity

import com.app.domain.model.TransportKind
import com.app.domain.model.TransportStatus
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value

/**
 * Connectivity panel hosted as a sheet [ChildSlot][com.arkivanov.decompose.router.slot.ChildSlot]
 * child: live per-transport status and the user-initiated re-enable action. Dismissal is owned by
 * the slot host (the chats component), not the panel itself.
 */
interface ConnectivityComponent {

    val model: Value<Model>

    fun onEnableClicked(kind: TransportKind)

    data class Model(
        val statuses: List<TransportStatus>,
    )

    fun interface Factory {
        fun create(componentContext: ComponentContext): ConnectivityComponent
    }
}
