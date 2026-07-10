package com.app.transport.nostr

import com.app.transport.crypto.Sha256
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the geohash presence heartbeat (Nostr kind 20001) against the reference iOS
 * `NostrProtocol.createGeohashPresenceEvent`:
 *
 *     let tags = [["g", geohash]]
 *     NostrEvent(pubkey:, createdAt: Date(), kind: .geohashPresence, tags: tags, content: "")
 *         .sign(with: senderIdentity.schnorrSigningKey())
 *
 * So: exactly one tag, no `n` nickname tag, empty content, signed by the per-geohash derived
 * identity. There is deliberately no expiry/TTL tag — kind 20001 sits in Nostr's ephemeral range
 * (20000–29999), which relays forward without persisting; that range IS the expiry mechanism, and
 * readers age presence out locally instead.
 *
 * The event id is the SHA-256 of the NIP-01 canonical array
 * `[0, pubkey, created_at, kind, tags, content]`, which this test rebuilds by hand.
 */
class GeohashPresenceEventPinTest {

    private companion object {
        // Fixed key so the pubkey (and therefore the canonical serialization) is deterministic.
        const val PRIVATE_KEY = "0000000000000000000000000000000000000000000000000000000000000001"
        const val GEOHASH = "u4pru"
    }

    private val identity = NostrIdentity.fromPrivateKey(PRIVATE_KEY)

    private fun ByteArray.hex() = joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }

    @Test
    fun presence_event_has_kind_20001_one_geohash_tag_and_empty_content() = runTest {
        val event = NostrProtocol.createGeohashPresenceEvent(GEOHASH, identity)

        assertEquals(20001, event.kind)
        assertEquals(NostrKind.GEOHASH_PRESENCE, event.kind)
        assertEquals(listOf(listOf("g", GEOHASH)), event.tags)
        assertEquals("", event.content)
        assertEquals(identity.publicKeyHex, event.pubkey)
    }

    /** No nickname tag: presence is for counting participants, not naming them. */
    @Test
    fun presence_event_carries_no_nickname_and_no_expiry_tag() = runTest {
        val event = NostrProtocol.createGeohashPresenceEvent(GEOHASH, identity)

        assertTrue(event.tags.none { it.firstOrNull() == "n" })
        assertTrue(event.tags.none { it.firstOrNull() == "expiration" })
        assertEquals(1, event.tags.size)
    }

    /**
     * Byte-level pin of the signed content: the id must be SHA-256 over the exact NIP-01 canonical
     * array, rebuilt here as a literal string rather than via the production serializer.
     */
    @Test
    fun presence_event_id_is_sha256_of_the_nip01_canonical_array() = runTest {
        val event = NostrProtocol.createGeohashPresenceEvent(GEOHASH, identity)

        val canonical = """[0,"${identity.publicKeyHex}",${event.createdAt},20001,[["g","$GEOHASH"]],""]"""
        val expectedId = Sha256.digest(canonical.encodeToByteArray()).hex()

        assertEquals(expectedId, event.id)
    }

    @Test
    fun presence_event_is_signed_by_the_geohash_identity() = runTest {
        val event = NostrProtocol.createGeohashPresenceEvent(GEOHASH, identity)

        assertTrue(event.isValidSignature())
    }

    /** A distinct geohash yields a distinct tag; the derived identity is the caller's concern. */
    @Test
    fun presence_event_tags_the_geohash_it_was_created_for() = runTest {
        val event = NostrProtocol.createGeohashPresenceEvent("9q8yy", identity)

        assertEquals(listOf(listOf("g", "9q8yy")), event.tags)
    }
}
