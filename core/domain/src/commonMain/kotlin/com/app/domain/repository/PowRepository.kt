package com.app.domain.repository

import kotlinx.coroutines.flow.Flow

/** A selectable proof-of-work difficulty with a human-readable label. */
data class PowDifficultyLevel(val difficulty: Int, val label: String)

/**
 * Proof-of-work preference for Nostr publishing: whether PoW mining is enabled and the target
 * difficulty. The transport layer reads the same preference.
 */
interface PowRepository {

    fun observePowEnabled(): Flow<Boolean>

    fun observePowDifficulty(): Flow<Int>

    suspend fun setPowEnabled(enabled: Boolean)

    suspend fun setPowDifficulty(difficulty: Int)

    /** Selectable difficulty presets (value + label) for the settings UI. */
    fun difficultyLevels(): List<PowDifficultyLevel>
}
