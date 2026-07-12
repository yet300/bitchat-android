package com.yet.bitmessage.feature.chats.conversations.groups.list

import com.app.common.decompose.asValue
import com.app.domain.repository.GroupRepository
import com.app.domain.repository.PeerRepository
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
import com.yet.bitmessage.feature.chats.conversations.groups.list.store.GroupListStore
import com.yet.bitmessage.feature.chats.conversations.groups.list.store.GroupListStoreFactory
import dev.zacsweers.metro.Inject

internal class DefaultGroupListComponent(
    componentContext: ComponentContext,
    storeFactory: GroupListStoreFactory,
    private val onGroupSelected: (groupIdHex: String, name: String) -> Unit,
    private val onClose: () -> Unit,
) : GroupListComponent, ComponentContext by componentContext {

    private val store = instanceKeeper.getStore { storeFactory.create() }
    private val dialogNav = SlotNavigation<GroupDialog>()

    override val model: Value<GroupListComponent.Model> = store.asValue().map { state ->
        GroupListComponent.Model(
            isLoading = state.isLoading,
            groups = state.groups,
            error = state.error,
        )
    }

    override val dialog: Value<ChildSlot<*, GroupDialog>> =
        childSlot(
            source = dialogNav,
            // Transient UI overlays — not worth persisting across process death.
            serializer = null,
            handleBackButton = true,
            childFactory = { config, _ -> config },
        )

    override fun onGroupClicked(groupIdHex: String, name: String) = onGroupSelected(groupIdHex, name)

    override fun onCreateClicked() = dialogNav.activate(GroupDialog.Create)

    override fun onInviteClicked(groupIdHex: String) =
        dialogNav.activate(GroupDialog.Invite(groupIdHex, store.state.invitablePeers))

    override fun onLeave(groupIdHex: String) = store.accept(GroupListStore.Intent.Leave(groupIdHex))

    override fun onSubmitCreate(name: String) {
        store.accept(GroupListStore.Intent.Create(name))
        dialogNav.dismiss()
    }

    override fun onSubmitInvite(groupIdHex: String, peerId: String) {
        store.accept(GroupListStore.Intent.Invite(groupIdHex, peerId))
        dialogNav.dismiss()
    }

    override fun onDismissDialog() = dialogNav.dismiss()

    override fun onCloseClicked() = onClose()
}

@Inject
internal class DefaultGroupListComponentFactory(
    private val storeFactory: StoreFactory,
    private val groupRepository: GroupRepository,
    private val peerRepository: PeerRepository,
) : GroupListComponent.Factory {
    override fun create(
        componentContext: ComponentContext,
        onGroupSelected: (groupIdHex: String, name: String) -> Unit,
        onClose: () -> Unit,
    ): GroupListComponent = DefaultGroupListComponent(
        componentContext = componentContext,
        storeFactory = GroupListStoreFactory(storeFactory, groupRepository, peerRepository),
        onGroupSelected = onGroupSelected,
        onClose = onClose,
    )
}
