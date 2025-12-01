package com.bitchat.android.feature.chat.sheet.about.integration

import com.bitchat.android.feature.chat.sheet.about.AboutComponent
import com.bitchat.android.feature.chat.sheet.about.store.AboutStore

internal val stateToModel: (AboutStore.State) -> AboutComponent.Model = { state ->
    AboutComponent.Model(
        versionName = state.versionName,
        themePreference = state.themePreference,
        powEnabled = state.powEnabled,
        powDifficulty = state.powDifficulty,
        torStatus = state.torStatus
    )
}
