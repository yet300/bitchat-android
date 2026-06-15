package com.yet.bitmessage.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import com.yet.bitmessage.feature.root.RootComponent
import com.yet.bitmessage.ui.screen.root.RootContent
import com.yet.bitmessage.ui.theme.BitMessageTheme

@Composable
fun App(rootComponent: RootComponent) {
    val theme by rootComponent.themeMode.subscribeAsState()

    BitMessageTheme(theme = theme) {
        RootContent(component = rootComponent)
    }
}
