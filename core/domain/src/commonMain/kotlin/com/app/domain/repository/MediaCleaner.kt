package com.app.domain.repository

/** Deletes all received and sent media files from local storage. Called during panic wipe. */
interface MediaCleaner {
    suspend fun wipeMedia()
}
