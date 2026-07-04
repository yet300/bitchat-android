package com.app.transport.nostr

import com.app.common.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages Proof of Work preferences for Nostr events, over the domain [SettingsStore] port.
 *
 * App-scoped singleton: persisted values are loaded once on construction; the reactive
 * [powEnabled] / [powDifficulty] / [isMining] flows are shared process-wide.
 */
class PoWPreferenceManager(
    private val settings: SettingsStore,
) {

    // State flows for reactive UI
    private val _powEnabled = MutableStateFlow(settings.getBoolean(KEY_POW_ENABLED, DEFAULT_POW_ENABLED))
    val powEnabled: StateFlow<Boolean> = _powEnabled.asStateFlow()

    private val _powDifficulty = MutableStateFlow(settings.getInt(KEY_POW_DIFFICULTY, DEFAULT_POW_DIFFICULTY))
    val powDifficulty: StateFlow<Int> = _powDifficulty.asStateFlow()

    // Mining state for animated indicators
    private val _isMining = MutableStateFlow(false)
    val isMining: StateFlow<Boolean> = _isMining.asStateFlow()

    /**
     * Get current PoW enabled state
     */
    fun isPowEnabled(): Boolean = _powEnabled.value

    /**
     * Set PoW enabled state
     */
    fun setPowEnabled(enabled: Boolean) {
        _powEnabled.value = enabled
        settings.putBoolean(KEY_POW_ENABLED, enabled)
    }

    /**
     * Get current PoW difficulty setting
     */
    fun getPowDifficulty(): Int = _powDifficulty.value

    /**
     * Set PoW difficulty (clamped between 0 and 32)
     */
    fun setPowDifficulty(difficulty: Int) {
        val clampedDifficulty = difficulty.coerceIn(0, 32)
        _powDifficulty.value = clampedDifficulty
        settings.putInt(KEY_POW_DIFFICULTY, clampedDifficulty)
    }

    /**
     * Get current settings as a data class
     */
    data class PoWSettings(
        val enabled: Boolean,
        val difficulty: Int
    )

    /**
     * Get current settings
     */
    fun getCurrentSettings(): PoWSettings = PoWSettings(
        enabled = _powEnabled.value,
        difficulty = _powDifficulty.value
    )

    /**
     * Reset to default settings
     */
    fun resetToDefaults() {
        setPowEnabled(DEFAULT_POW_ENABLED)
        setPowDifficulty(DEFAULT_POW_DIFFICULTY)
    }

    /**
     * Get difficulty levels with descriptions for UI
     */
    fun getDifficultyLevels(): List<Pair<Int, String>> = listOf(
        0 to "Disabled (no PoW)",
        8 to "Very Low (instant)",
        12 to "Low (~0.1s)",
        16 to "Medium (~2s)",
        20 to "High (~30s)",
        24 to "Very High (~8m)",
        28 to "Extreme (~2h)",
        32 to "Maximum (~8h)"
    )

    /**
     * Get current mining state
     */
    fun isMining(): Boolean = _isMining.value

    /**
     * Start mining state - triggers animated indicators
     */
    fun startMining() {
        _isMining.value = true
    }

    /**
     * Stop mining state - stops animated indicators
     */
    fun stopMining() {
        _isMining.value = false
    }

    private companion object {
        const val KEY_POW_ENABLED = "pow_enabled"
        const val KEY_POW_DIFFICULTY = "pow_difficulty"

        const val DEFAULT_POW_ENABLED = false
        const val DEFAULT_POW_DIFFICULTY = 12 // Reasonable default for geohash spam prevention
    }
}
