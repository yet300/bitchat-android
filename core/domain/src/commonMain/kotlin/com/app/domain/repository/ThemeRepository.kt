package com.app.domain.repository

import com.app.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

/** App-theme preference. The platform implementation persists it and drives [observeTheme]. */
interface ThemeRepository {

    fun observeTheme(): Flow<ThemeMode>

    suspend fun setTheme(mode: ThemeMode)
}
