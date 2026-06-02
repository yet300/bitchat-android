package com.bitchat.android.core.domain.model

/** Наша собственная личность. */
data class MyIdentity(
    val peerId: PeerId,
    val fingerprint: Fingerprint,
    val nickname: String,
    val nostrNpub: String? = null,
)
