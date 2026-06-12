package com.yet.bitmessage.ui

import androidx.compose.runtime.Composable
import com.yet.bitmessage.feature.root.RootComponent
import com.yet.bitmessage.ui.screen.root.RootContent
import com.yet.bitmessage.ui.theme.BitMessageTheme

@Composable
fun App(rootComponent: RootComponent) {
    BitMessageTheme {
        RootContent(component = rootComponent)
    }
}
