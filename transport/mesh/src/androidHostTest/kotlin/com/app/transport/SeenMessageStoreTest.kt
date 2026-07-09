package com.app.transport

import com.app.crypto.identity.SecureIdentityStateManager
import com.app.crypto.secure.SecureKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeenMessageStoreTest {

    private class InMemoryStore : SecureKeyValueStore {
        val map = mutableMapOf<String, String>()
        val sets = mutableMapOf<String, Set<String>>()
        var putCount = 0

        override fun getString(key: String): String? = map[key]
        override fun putString(key: String, value: String) {
            putCount++
            map[key] = value
        }
        override fun getStringSet(key: String): Set<String>? = sets[key]
        override fun putStringSet(key: String, values: Set<String>) { sets[key] = values }
        override fun contains(key: String): Boolean = map.containsKey(key) || sets.containsKey(key)
        override fun remove(vararg keys: String) { keys.forEach { map.remove(it); sets.remove(it) } }
        override suspend fun clear() { map.clear(); sets.clear() }
    }

    private fun newBacking() = InMemoryStore()

    @Test
    fun markPersistsAndReloadsAcrossInstances() {
        val backing = newBacking()
        val store = SeenMessageStore(SecureIdentityStateManager(backing))

        store.markDelivered("d1")
        store.markRead("r1")
        store.markReadAll(listOf("r2", "r3"))
        store.flush() // writes are now debounced; force the synchronous write for read-back

        val reloaded = SeenMessageStore(SecureIdentityStateManager(backing))
        assertTrue(reloaded.hasDelivered("d1"))
        assertTrue(reloaded.hasRead("r1"))
        assertTrue(reloaded.hasRead("r2"))
        assertTrue(reloaded.hasRead("r3"))
        assertFalse(reloaded.hasDelivered("r1"))
        assertFalse(reloaded.hasRead("d1"))
    }

    @Test
    fun markReadAllWritesStorageOnce() {
        val backing = newBacking()
        val store = SeenMessageStore(SecureIdentityStateManager(backing))

        val before = backing.putCount
        // Debounced: the mark itself does NOT write inline (that is the S15 fix — it must not
        // stall the caller's ingest monitor). flush() then produces a single write.
        store.markReadAll(listOf("a", "b", "c"))
        assertEquals("mark must not write synchronously", before, backing.putCount)
        store.flush()
        assertEquals("flush writes the coalesced snapshot once", before + 1, backing.putCount)

        // Empty batch bumps no version, so a subsequent flush is a no-op (version check).
        store.markReadAll(emptyList())
        store.flush()
        assertEquals(before + 1, backing.putCount)
    }

    @Test
    fun debouncedWritesCoalesceManyMarksIntoOneWrite() {
        val backing = newBacking()
        val store = SeenMessageStore(SecureIdentityStateManager(backing))

        val before = backing.putCount
        repeat(50) { store.markDelivered("d$it") }
        assertEquals("a burst of 50 ACKs must not produce 50 inline writes", before, backing.putCount)

        store.flush()
        assertEquals("the coalesced burst flushes as a single write", before + 1, backing.putCount)

        val reloaded = SeenMessageStore(SecureIdentityStateManager(backing))
        repeat(50) { assertTrue("d$it must be persisted", reloaded.hasDelivered("d$it")) }
    }

    @Test
    fun clearPersistsEmptyState() {
        val backing = newBacking()
        val store = SeenMessageStore(SecureIdentityStateManager(backing))
        store.markDelivered("d1")
        store.markRead("r1")

        store.clear()

        val reloaded = SeenMessageStore(SecureIdentityStateManager(backing))
        assertFalse(reloaded.hasDelivered("d1"))
        assertFalse(reloaded.hasRead("r1"))
    }
}
