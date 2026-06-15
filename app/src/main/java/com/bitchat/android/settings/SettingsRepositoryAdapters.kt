package com.bitchat.android.settings

import com.app.domain.repository.PowDifficultyLevel
import com.app.domain.repository.PowRepository
import com.app.domain.repository.TorRepository
import com.app.transport.net.TorMode
import com.app.transport.net.TorPreferenceManager
import com.app.transport.nostr.PoWPreferenceManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Thin adapters exposing the graph-owned transport preference managers (Tor, PoW) through the pure
 * domain settings ports, so the feature module depends only on :core:domain (DIP). Theme is backed
 * directly by :core:data over the durable SettingsStore (no dependency on the legacy app manager).
 */
internal class TorRepositoryImpl(
    private val manager: TorPreferenceManager,
) : TorRepository {
    override fun observeTorEnabled(): Flow<Boolean> = manager.modeFlow.map { it == TorMode.ON }
    override suspend fun setTorEnabled(enabled: Boolean) = manager.set(if (enabled) TorMode.ON else TorMode.OFF)
}

internal class PowRepositoryImpl(
    private val manager: PoWPreferenceManager,
) : PowRepository {
    override fun observePowEnabled(): Flow<Boolean> = manager.powEnabled
    override fun observePowDifficulty(): Flow<Int> = manager.powDifficulty
    override suspend fun setPowEnabled(enabled: Boolean) = manager.setPowEnabled(enabled)
    override suspend fun setPowDifficulty(difficulty: Int) = manager.setPowDifficulty(difficulty)
    override fun difficultyLevels(): List<PowDifficultyLevel> =
        manager.getDifficultyLevels().map { PowDifficultyLevel(it.first, it.second) }
}
