package com.app.domain.repository

import com.app.domain.model.LocationNote
import kotlinx.coroutines.flow.Flow

/**
 * Geo-anchored notes feed for a building-level geohash. Relay subscription, identity derivation and
 * dedup are infrastructure handled by the implementation.
 */
interface LocationNotesRepository {

    /** Notes for the selected geohash (+ its immediate neighbours), newest first. */
    fun observeNotes(): Flow<List<LocationNote>>

    /** True until the first batch for the selected geohash has loaded. */
    fun observeLoading(): Flow<Boolean>

    /** Select the building-level geohash (8 base32 chars) to read/post notes for. */
    fun select(geohash: String)

    /** Post a note to the selected geohash. */
    suspend fun send(content: String, nickname: String?)
}
