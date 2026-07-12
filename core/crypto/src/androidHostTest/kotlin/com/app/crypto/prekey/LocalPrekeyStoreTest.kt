package com.app.crypto.prekey

import com.app.crypto.secure.InMemorySecureKeyValueStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * LocalPrekeyStore lifecycle, mirroring the reference iOS store: an initial batch of 8 unconsumed
 * prekeys, a strictly-monotonic generatedAt, the 48h consumed-grace window with deletion on
 * replenish, low-water top-up below 3 unconsumed, persistence round-trip, and panic wipe.
 */
class LocalPrekeyStoreTest {

    private var clock = 1_000_000_000L
    private val store = InMemorySecureKeyValueStore()
    private val prekeys = LocalPrekeyStore(store) { clock }

    private fun freshStore() = LocalPrekeyStore(store) { clock }

    @Test
    fun `initial batch mints eight unconsumed prekeys`() {
        val (bundle, generatedAt) = prekeys.currentBundlePrekeys()
        assertEquals(8, bundle.size)
        assertEquals(8, prekeys.unconsumedCount())
        assertTrue(generatedAt > 0uL)
        // IDs are sequential from 0 and public keys are 32 bytes.
        assertEquals((0u until 8u).toList(), bundle.map { it.id })
        assertTrue(bundle.all { it.publicKey.size == 32 })
    }

    @Test
    fun `public key derives deterministically from the stored private`() {
        val bundle = prekeys.currentBundlePrekeys().first
        val id = bundle.first().id
        val priv = prekeys.privateKey(id)
        assertNotNull(priv)
        // Re-deriving via a fresh store instance from persisted state yields the same public key.
        val reloaded = freshStore().currentBundlePrekeys().first.first { it.id == id }
        assertContentEquals(bundle.first().publicKey, reloaded.publicKey)
    }

    @Test
    fun `markConsumed is idempotent and advances generatedAt strictly`() {
        val (bundle, gen0) = prekeys.currentBundlePrekeys()
        val id = bundle.first().id

        assertTrue(prekeys.markConsumed(id), "first consume retires the prekey")
        val gen1 = prekeys.currentBundlePrekeys().second
        assertTrue(gen1 > gen0, "generatedAt advances on consumption")

        assertFalse(prekeys.markConsumed(id), "redelivery within grace does not re-retire")
    }

    @Test
    fun `consumed private survives the grace window then is deleted`() {
        val id = prekeys.currentBundlePrekeys().first.first().id
        prekeys.markConsumed(id)

        // Within grace: still openable for spray-and-wait redeliveries.
        clock += LocalPrekeyStore.Policy.CONSUMED_GRACE_MS - 1
        assertNotNull(prekeys.privateKey(id), "consumed key survives inside the grace window")

        // Past grace: privateKey lookup refuses it, and replenish prunes it for good.
        clock += 2
        assertNull(prekeys.privateKey(id), "grace-expired key is refused")
        prekeys.replenishIfNeeded()
        assertNull(prekeys.privateKey(id), "grace-expired key is pruned")
    }

    @Test
    fun `consuming below the threshold tops the batch back up to eight`() {
        val bundle = prekeys.currentBundlePrekeys().first
        // Consume down to 2 unconsumed (below the threshold of 3).
        bundle.take(6).forEach { prekeys.markConsumed(it.id) }
        assertEquals(2, prekeys.unconsumedCount())

        val topped = prekeys.replenishIfNeeded()
        assertTrue(topped, "low-water replenish reports a bundle change")
        assertEquals(8, prekeys.unconsumedCount(), "batch tops back up to 8 unconsumed")
    }

    @Test
    fun `consuming just above the threshold does not replenish`() {
        val bundle = prekeys.currentBundlePrekeys().first
        // Consume down to exactly 3 unconsumed (threshold is <3).
        bundle.take(5).forEach { prekeys.markConsumed(it.id) }
        assertEquals(3, prekeys.unconsumedCount())
        assertFalse(prekeys.replenishIfNeeded(), "at the threshold no top-up happens")
        assertEquals(3, prekeys.unconsumedCount())
    }

    @Test
    fun `state persists across store instances`() {
        val (bundle, generatedAt) = prekeys.currentBundlePrekeys()
        val id = bundle.first().id
        prekeys.markConsumed(id)
        val genAfterConsume = prekeys.currentBundlePrekeys().second

        val reloaded = freshStore()
        assertEquals(7, reloaded.unconsumedCount(), "consumed count survives reload")
        assertEquals(genAfterConsume, reloaded.currentBundlePrekeys().second)
        assertTrue(genAfterConsume > generatedAt)
    }

    @Test
    fun `wipe drops all privates and the persisted entry`() {
        val id = prekeys.currentBundlePrekeys().first.first().id
        prekeys.wipe()
        assertNull(prekeys.privateKey(id))
        assertFalse(store.contains("one_time_prekeys_v1"))
        // A fresh store mints a brand-new batch (on first bundle request) rather than resurrecting
        // the old one — minting is lazy, so unconsumedCount alone would still read 0 here.
        assertEquals(8, freshStore().currentBundlePrekeys().first.size)
    }
}
