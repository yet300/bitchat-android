package com.bitchat.android.core.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * Ordinary (non-secret) application settings.
 */
interface SettingsRepository {

    fun observeNickname(): Flow<String>

    suspend fun setNickname(value: String)

    var locationServicesEnabled: Boolean
}
