package com.app.domain.model

/** Our own identity. */
data class MyIdentity(
    val peerId: PeerId,
    val fingerprint: Fingerprint,
    val nickname: String,
    val nostrNpub: String? = null,
)
