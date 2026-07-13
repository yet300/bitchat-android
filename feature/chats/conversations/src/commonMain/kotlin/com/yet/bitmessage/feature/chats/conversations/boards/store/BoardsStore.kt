package com.yet.bitmessage.feature.chats.conversations.boards.store

import com.app.domain.model.BoardPost
import com.arkivanov.mvikotlin.core.store.Store

internal interface BoardsStore :
    Store<BoardsStore.Intent, BoardsStore.State, Nothing> {

    data class State(
        val isLoading: Boolean = true,
        /** Current board: a geohash, or "" for the mesh-local board. */
        val geohash: String = "",
        val posts: List<BoardPost> = emptyList(),
    )

    sealed interface Intent {
        /** Switch the visible board ("" = mesh-local). */
        data class SelectBoard(val geohash: String) : Intent
        data class CreatePost(val content: String, val urgent: Boolean, val expiryDays: Int) : Intent
        data class Delete(val postIdHex: String) : Intent
    }

    sealed interface Action {
        data object Subscribe : Action
    }

    sealed interface Msg {
        data class BoardChanged(val geohash: String) : Msg
        data class Loaded(val posts: List<BoardPost>) : Msg
    }
}
