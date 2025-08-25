package com.bitchat.android.model

import com.bitchat.android.protocol.BitchatPacket

data class RoutedPacket(
    val packet: BitchatPacket,
    val peerID: String? = null,
    val relayAddress: String? = null
)