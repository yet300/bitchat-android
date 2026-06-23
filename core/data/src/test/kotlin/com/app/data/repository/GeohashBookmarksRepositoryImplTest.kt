package com.app.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeohashBookmarksRepositoryImplTest {

    private fun repo(db: InMemoryDatabase = InMemoryDatabase()) =
        GeohashBookmarksRepositoryImpl(db.geohashDao)

    @Test
    fun toggle_adds_then_removes_and_normalises() = runTest {
        val repo = repo()
        assertFalse(repo.observeIsBookmarked("u4pru").first())

        repo.toggle("#U4PRU") // normalised: lowercase, strip '#'
        assertTrue(repo.observeIsBookmarked("u4pru").first())
        assertEquals(listOf("u4pru"), repo.observeBookmarks().first())

        repo.toggle("u4pru")
        assertFalse(repo.observeIsBookmarked("u4pru").first())
        assertTrue(repo.observeBookmarks().first().isEmpty())
    }

    @Test
    fun newest_bookmark_is_first() = runTest {
        val repo = repo()
        repo.toggle("9q8")
        repo.toggle("bcd")
        assertEquals(listOf("bcd", "9q8"), repo.observeBookmarks().first())
    }
}
