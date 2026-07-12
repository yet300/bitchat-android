package com.yet.bitmessage.feature.chats.conversations.boards.store

import com.app.domain.repository.BoardRepository
import com.arkivanov.mvikotlin.core.store.Reducer
import com.arkivanov.mvikotlin.core.store.SimpleBootstrapper
import com.arkivanov.mvikotlin.core.store.Store
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.arkivanov.mvikotlin.extensions.coroutines.CoroutineExecutor
import kotlinx.coroutines.launch

internal class BoardsStoreFactory(
    private val storeFactory: StoreFactory,
    private val boardRepository: BoardRepository,
) {
    fun create(): BoardsStore =
        object : BoardsStore,
            Store<BoardsStore.Intent, BoardsStore.State, Nothing> by storeFactory.create(
                name = "BoardsStore",
                initialState = BoardsStore.State(),
                bootstrapper = SimpleBootstrapper(BoardsStore.Action.Subscribe),
                executorFactory = ::ExecutorImpl,
                reducer = ReducerImpl,
            ) {}

    private object ReducerImpl : Reducer<BoardsStore.State, BoardsStore.Msg> {
        override fun BoardsStore.State.reduce(msg: BoardsStore.Msg): BoardsStore.State =
            when (msg) {
                is BoardsStore.Msg.BoardChanged -> copy(geohash = msg.geohash, isLoading = true, posts = emptyList())
                is BoardsStore.Msg.Loaded -> copy(isLoading = false, posts = msg.posts)
            }
    }

    private inner class ExecutorImpl :
        CoroutineExecutor<BoardsStore.Intent, BoardsStore.Action, BoardsStore.State, BoardsStore.Msg, Nothing>() {

        override fun executeAction(action: BoardsStore.Action) {
            when (action) {
                BoardsStore.Action.Subscribe -> {
                    reload(state().geohash)
                    scope.launch {
                        boardRepository.postArrivals.collect { post ->
                            if (post.geohash == state().geohash) reload(state().geohash)
                        }
                    }
                }
            }
        }

        override fun executeIntent(intent: BoardsStore.Intent) {
            when (intent) {
                is BoardsStore.Intent.SelectBoard -> {
                    dispatch(BoardsStore.Msg.BoardChanged(intent.geohash))
                    reload(intent.geohash)
                }
                is BoardsStore.Intent.CreatePost -> scope.launch {
                    boardRepository.createPost(intent.content, state().geohash, intent.urgent, intent.expiryDays)
                    reload(state().geohash)
                }
                is BoardsStore.Intent.Delete -> scope.launch {
                    boardRepository.deletePost(intent.postIdHex)
                    reload(state().geohash)
                }
            }
        }

        private fun reload(geohash: String) = scope.launch {
            dispatch(BoardsStore.Msg.Loaded(boardRepository.posts(geohash)))
        }
    }
}
