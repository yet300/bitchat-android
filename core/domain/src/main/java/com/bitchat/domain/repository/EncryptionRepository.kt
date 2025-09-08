package com.bitchat.domain.repository

import com.bitchat.domain.model.NoiseSessionState

interface EncryptionRepository {
    fun getCombinedPublicKeyData(): ByteArray
    fun getStaticPublicKey(): ByteArray?
    fun getSigningPublicKey(): ByteArray?
    fun signData(data: ByteArray): ByteArray?
    fun encrypt(data: ByteArray, peerID: String): ByteArray
    fun decrypt(data: ByteArray, peerID: String): ByteArray
    fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKeyBytes: ByteArray): Boolean
    fun hasEstablishedSession(peerID: String): Boolean
    fun initiateHandshake(peerID: String): ByteArray?

    fun sign(data: ByteArray): ByteArray

    fun verify(signature: ByteArray, data: ByteArray, peerID: String): Boolean

    fun getEstablishedPeers(): List<String>

    fun getSessionsNeedingRekey(): List<String>

    fun initiateRekey(peerID: String): ByteArray?

    fun getDebugInfo(): String

    fun getPeerIdentityKey(peerID: String): ByteArray?

    fun shouldShowEncryptionIcon(peerID: String): Boolean
    fun getPeerFingerprint(peerID: String): String?

    fun getCurrentPeerID(fingerprint: String): String?

    fun processHandshakeMessage(data: ByteArray, peerID: String): ByteArray?

    fun encryptChannelMessage(message: String, channel: String): ByteArray?

    fun getSessionState(peerID: String): NoiseSessionState

    fun removePeer(peerID: String)

    @Throws(Exception::class)
    fun addPeerPublicKey(peerID: String, publicKeyData: ByteArray)

    fun updatePeerIDMapping(oldPeerID: String?, newPeerID: String, fingerprint: String)

    fun setChannelPassword(password: String, channel: String)

    fun shutdown()

    fun removeChannelPassword(channel: String)

    fun decryptChannelMessage(encryptedData: ByteArray, channel: String): String?

    fun getIdentityFingerprint(): String
    fun clearPersistentIdentity()
}