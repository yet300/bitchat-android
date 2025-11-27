package com.bitchat.android.feature.about.integration

import com.bitchat.android.feature.about.AboutComponent
import com.bitchat.android.feature.about.store.AboutStore

internal val stateToModel: (AboutStore.State) -> AboutComponent.Model = { state ->
    AboutComponent.Model(
        versionName = state.versionName,
        themePreference = state.themePreference,
        powEnabled = state.powEnabled,
        powDifficulty = state.powDifficulty,
        torStatus = state.torStatus
    )
}
