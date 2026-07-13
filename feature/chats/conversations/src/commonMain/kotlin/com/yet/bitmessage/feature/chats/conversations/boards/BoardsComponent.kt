package com.yet.bitmessage.feature.chats.conversations.boards

import com.app.domain.model.BoardPost
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.value.Value

/**
 * Geohash bulletin boards (0x23): the live posts for one board (a geohash, or "" for the mesh-local
 * board), urgent first then newest. Anyone may post; the only deletion is an author-signed tombstone
 * of your own post. The compose overlay is a Decompose [ChildSlot] ([dialog]).
 */
interface BoardsComponent {

    val model: Value<Model>

    val dialog: Value<ChildSlot<*, BoardDialog>>

    /** Switch the visible board ("" = mesh-local). */
    fun onSelectBoard(geohash: String)

    fun onCreateClicked()

    fun onSubmitCreate(content: String, urgent: Boolean, expiryDays: Int)

    /** Tombstone one of our own posts. */
    fun onDelete(postIdHex: String)

    fun onDismissDialog()

    fun onCloseClicked()

    data class Model(
        val isLoading: Boolean,
        val geohash: String,
        val posts: List<BoardPost>,
    )

    fun interface Factory {
        fun create(
            componentContext: ComponentContext,
            onClose: () -> Unit,
        ): BoardsComponent
    }
}
