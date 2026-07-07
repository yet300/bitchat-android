package com.app.data.repository

import com.app.data.tor.TorUserPreferenceManager
import com.app.domain.repository.PowDifficultyLevel
import com.app.domain.repository.PowRepository
import com.app.domain.repository.TorRepository
import com.app.transport.nostr.PoWPreferenceManager
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow

/**
 * Thin adapters exposing the graph-owned transport preference managers (Tor, PoW) through the pure
 * domain settings ports, so the feature module depends only on :core:domain (DIP). Theme is backed
 * directly by :core:data over the durable SettingsStore (no dependency on the legacy app manager).
 */
@Inject
class TorRepositoryImpl(
    private val userPreference: TorUserPreferenceManager,
) : TorRepository {
    // Reflects/controls the user's Tor *preference*; the effective mode is derived by
    // TorActivationController from this plus the activation policy.
    override fun observeTorEnabled(): Flow<Boolean> = userPreference.enabledFlow
    override suspend fun setTorEnabled(enabled: Boolean) = userPreference.set(enabled)
}

@Inject
class PowRepositoryImpl(
    private val manager: PoWPreferenceManager,
) : PowRepository {
    override fun observePowEnabled(): Flow<Boolean> = manager.powEnabled
    override fun observePowDifficulty(): Flow<Int> = manager.powDifficulty
    override suspend fun setPowEnabled(enabled: Boolean) = manager.setPowEnabled(enabled)
    override suspend fun setPowDifficulty(difficulty: Int) = manager.setPowDifficulty(difficulty)
    override fun difficultyLevels(): List<PowDifficultyLevel> =
        manager.getDifficultyLevels().map { PowDifficultyLevel(it.first, it.second) }
}
