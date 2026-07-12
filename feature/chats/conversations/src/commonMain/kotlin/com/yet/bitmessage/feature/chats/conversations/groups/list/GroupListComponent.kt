package com.yet.bitmessage.feature.chats.conversations.groups.list

import com.app.domain.model.GroupInfo
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.value.Value

/**
 * Private-group list (0x25): the groups this device belongs to, with create, creator-only invite,
 * and leave. Create/invite overlays are a Decompose [ChildSlot] ([dialog]); tapping a group opens
 * its chat (navigation owned by the parent coordinator).
 */
interface GroupListComponent {

    val model: Value<Model>

    val dialog: Value<ChildSlot<*, GroupDialog>>

    fun onGroupClicked(groupIdHex: String, name: String)

    fun onCreateClicked()

    /** Open the invite picker for a group this device created. */
    fun onInviteClicked(groupIdHex: String)

    fun onLeave(groupIdHex: String)

    fun onSubmitCreate(name: String)

    fun onSubmitInvite(groupIdHex: String, peerId: String)

    fun onDismissDialog()

    fun onCloseClicked()

    data class Model(
        val isLoading: Boolean,
        val groups: List<GroupInfo>,
        val error: String?,
    )

    fun interface Factory {
        fun create(
            componentContext: ComponentContext,
            onGroupSelected: (groupIdHex: String, name: String) -> Unit,
            onClose: () -> Unit,
        ): GroupListComponent
    }
}
