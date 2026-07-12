package com.yet.bitmessage.feature.chats.conversations.boards

import com.app.common.decompose.asValue
import com.app.domain.repository.BoardRepository
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.router.slot.SlotNavigation
import com.arkivanov.decompose.router.slot.activate
import com.arkivanov.decompose.router.slot.childSlot
import com.arkivanov.decompose.router.slot.dismiss
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.operator.map
import com.arkivanov.mvikotlin.core.instancekeeper.getStore
import com.arkivanov.mvikotlin.core.store.StoreFactory
import com.yet.bitmessage.feature.chats.conversations.boards.store.BoardsStore
import com.yet.bitmessage.feature.chats.conversations.boards.store.BoardsStoreFactory
import dev.zacsweers.metro.Inject

internal class DefaultBoardsComponent(
    componentContext: ComponentContext,
    storeFactory: BoardsStoreFactory,
    private val onClose: () -> Unit,
) : BoardsComponent, ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }
    private val dialogNav = SlotNavigation<BoardDialog>()

    override val model: Value<BoardsComponent.Model> = store.asValue().map { state ->
        BoardsComponent.Model(
            isLoading = state.isLoading,
            geohash = state.geohash,
            posts = state.posts,
        )
    }

    override val dialog: Value<ChildSlot<*, BoardDialog>> =
        childSlot(
            source = dialogNav,
            serializer = null,
            handleBackButton = true,
            childFactory = { config, _ -> config },
        )

    override fun onSelectBoard(geohash: String) = store.accept(BoardsStore.Intent.SelectBoard(geohash.trim()))

    override fun onCreateClicked() = dialogNav.activate(BoardDialog.Create)

    override fun onSubmitCreate(content: String, urgent: Boolean, expiryDays: Int) {
        store.accept(BoardsStore.Intent.CreatePost(content, urgent, expiryDays))
        dialogNav.dismiss()
    }

    override fun onDelete(postIdHex: String) = store.accept(BoardsStore.Intent.Delete(postIdHex))

    override fun onDismissDialog() = dialogNav.dismiss()

    override fun onCloseClicked() = onClose()
}

@Inject
internal class DefaultBoardsComponentFactory(
    private val storeFactory: StoreFactory,
    private val boardRepository: BoardRepository,
) : BoardsComponent.Factory {
    override fun create(
        componentContext: ComponentContext,
        onClose: () -> Unit,
    ): BoardsComponent = DefaultBoardsComponent(
        componentContext = componentContext,
        storeFactory = BoardsStoreFactory(storeFactory, boardRepository),
        onClose = onClose,
    )
}
