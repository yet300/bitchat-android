package com.bitchat.android.feature.root

import kotlinx.serialization.Serializable

@Serializable
sealed class DeepLinkData {
    @Serializable
    data class PrivateChat(
        val peerID: String,
        val senderNickname: String?
    ) : DeepLinkData()

    @Serializable
    data class GeohashChat(
        val geohash: String
    ) : DeepLinkData()
}
