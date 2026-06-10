@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.bitchat.android.ui

import com.app.transport.model.BitchatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Clock

/**
 * Covers the probe-decrypt channel password verification:
 * - wrong password is rejected when encrypted history exists
 * - correct password joins, stores the key and switches to the channel
 * - join with no encrypted history is provisional; first failed decrypt evicts the key
 */
class ChannelManagerPasswordTest {

    private companion object {
        const val CHANNEL = "#secret"
        const val MY_PEER_ID = "abcdef1234567890"
        const val PASSWORD = "correct-horse"
    }

    private lateinit var state: ChatState
    private lateinit var messageManager: MessageManager
    private lateinit var dataManager: DataManager
    private lateinit var channelManager: ChannelManager

    @Before
    fun setUp() {
        state = ChatState(CoroutineScope(Dispatchers.Unconfined))
        messageManager = mock()
        dataManager = mock()
        whenever(dataManager.channelCreators).thenReturn(emptyMap())
        whenever(dataManager.isChannelCreator(any(), any())).thenReturn(false)
        channelManager = ChannelManager(state, messageManager, dataManager, CoroutineScope(Dispatchers.Unconfined))

        state.setPasswordProtectedChannels(setOf(CHANNEL))
    }

    /** Same derivation as ChannelManager/iOS: PBKDF2-HMAC-SHA256, salt = channel name, 100k iterations. */
    private fun deriveKey(password: String, channelName: String): SecretKeySpec {
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(password.toCharArray(), channelName.toByteArray(), 100000, 256)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun encrypt(content: String, key: SecretKeySpec): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12) { it.toByte() }
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return iv + cipher.doFinal(content.toByteArray(Charsets.UTF_8))
    }

    private fun seedEncryptedHistory(content: String, password: String) {
        val message = BitchatMessage(
            sender = "peer1",
            content = "",
            timestamp = Clock.System.now(),
            channel = CHANNEL,
            encryptedContent = encrypt(content, deriveKey(password, CHANNEL)),
            isEncrypted = true,
        )
        state.setChannelMessages(mapOf(CHANNEL to listOf(message)))
    }

    @Test
    fun wrongPasswordIsRejectedAgainstEncryptedHistory() {
        seedEncryptedHistory("hello", PASSWORD)

        val joined = channelManager.joinChannel(CHANNEL, "wrong-password", MY_PEER_ID)

        assertFalse(joined)
        assertFalse(channelManager.hasChannelKey(CHANNEL))
        assertFalse(state.getJoinedChannelsValue().contains(CHANNEL))
    }

    @Test
    fun correctPasswordJoinsStoresKeyAndSwitches() {
        seedEncryptedHistory("hello", PASSWORD)

        val joined = channelManager.joinChannel(CHANNEL, PASSWORD, MY_PEER_ID)

        assertTrue(joined)
        assertTrue(channelManager.hasChannelKey(CHANNEL))
        assertEquals(PASSWORD, channelManager.getChannelPassword(CHANNEL))
        assertEquals(CHANNEL, state.getCurrentChannelValue())
        assertTrue(state.getJoinedChannelsValue().contains(CHANNEL))
        // Key actually decrypts channel traffic
        val ciphertext = encrypt("ping", deriveKey(PASSWORD, CHANNEL))
        assertEquals("ping", channelManager.decryptChannelMessage(ciphertext, CHANNEL))
    }

    @Test
    fun alreadyJoinedChannelVerifiesPasswordAndSwitches() {
        seedEncryptedHistory("hello", PASSWORD)
        state.setJoinedChannels(setOf(CHANNEL))

        val joined = channelManager.joinChannel(CHANNEL, PASSWORD, MY_PEER_ID)

        assertTrue(joined)
        assertTrue(channelManager.hasChannelKey(CHANNEL))
        assertEquals(CHANNEL, state.getCurrentChannelValue())
    }

    @Test
    fun joinWithoutHistoryIsProvisionalAndEvictedOnFirstFailedDecrypt() {
        // No encrypted messages in the channel: any password is provisionally accepted
        val joined = channelManager.joinChannel(CHANNEL, "maybe-wrong", MY_PEER_ID)

        assertTrue(joined)
        assertTrue(channelManager.hasChannelKey(CHANNEL))

        // First real ciphertext does not decrypt with the provisional key → key evicted
        val foreignCiphertext = encrypt("hello", deriveKey(PASSWORD, CHANNEL))
        assertNull(channelManager.decryptChannelMessage(foreignCiphertext, CHANNEL))
        assertFalse(channelManager.hasChannelKey(CHANNEL))
    }

    @Test
    fun provisionalKeyConfirmedByFirstSuccessfulDecryptSurvivesLaterGarbage() {
        val joined = channelManager.joinChannel(CHANNEL, PASSWORD, MY_PEER_ID)
        assertTrue(joined)

        // First decrypt succeeds → key is no longer provisional
        val ciphertext = encrypt("hello", deriveKey(PASSWORD, CHANNEL))
        assertEquals("hello", channelManager.decryptChannelMessage(ciphertext, CHANNEL))

        // Later garbage (e.g. corrupted packet) must NOT evict a confirmed key
        assertNull(channelManager.decryptChannelMessage(ByteArray(32) { 7 }, CHANNEL))
        assertTrue(channelManager.hasChannelKey(CHANNEL))
    }
}
