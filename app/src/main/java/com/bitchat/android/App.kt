package com.bitchat.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.bitchat.android.feature.root.RootComponent
import com.bitchat.android.ui.screens.root.RootContent
import com.bitchat.android.ui.theme.BitchatTheme

@Composable
fun App(component: RootComponent) {
    val model by component.model.subscribeAsState()
    BitchatTheme(
        themePref = model.theme
    ) {
        RootContent(component = component)
    }
}

