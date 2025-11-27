package com.bitchat.android.feature.root.integration

import com.bitchat.android.feature.root.RootComponent
import com.bitchat.android.feature.root.store.RootStore

internal val stateToModel: (RootStore.State) -> RootComponent.Model = { state ->
    RootComponent.Model(
        theme = state.themePreference
    )
}
