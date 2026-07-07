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
        store.markReadAll(listOf("a", "b", "c"))
        assertEquals(before + 1, backing.putCount)

        // Empty batch must not touch storage at all
        store.markReadAll(emptyList())
        assertEquals(before + 1, backing.putCount)
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
