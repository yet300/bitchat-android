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

    /** Favorite / unfavorite the mesh peer (by raw peer id). */
    fun onToggleFavorite(peerIdRaw: String)

    data class Model(
        val statuses: List<TransportStatus>,
        val peers: List<PeerRow>,
    )

    /** A mesh peer with its live link detail, for the connectivity panel's peer list. */
    data class PeerRow(
        val peerIdRaw: String,
        val name: String,
        val rssi: Int?,
        val isDirect: Boolean,
        val isConnected: Boolean,
        val isVerified: Boolean,
        val isFavorite: Boolean,
    )

    fun interface Factory {
        fun create(componentContext: ComponentContext): ConnectivityComponent
    }
}
