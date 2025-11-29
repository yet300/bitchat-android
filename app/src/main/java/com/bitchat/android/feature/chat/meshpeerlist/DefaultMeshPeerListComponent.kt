package com.bitchat.android.feature.chat.meshpeerlist

import com.arkivanov.decompose.ComponentContext

class DefaultMeshPeerListComponent(
    componentContext: ComponentContext,
    private val onDismissCallback: () -> Unit
) : MeshPeerListComponent, ComponentContext by componentContext {

    override fun onDismiss() {
        onDismissCallback()
    }
}