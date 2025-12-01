package com.bitchat.android.feature.chat.sheet.usersheet.integration

import com.bitchat.android.feature.chat.sheet.usersheet.UserSheetComponent
import com.bitchat.android.feature.chat.sheet.usersheet.store.UserSheetStore

internal val stateToModel: (UserSheetStore.State) -> UserSheetComponent.Model = { state ->
    UserSheetComponent.Model(
        targetNickname = state.targetNickname,
        selectedMessage = state.selectedMessage,
        isSelf = state.isSelf,
        isGeohashChannel = state.isGeohashChannel
    )
}
