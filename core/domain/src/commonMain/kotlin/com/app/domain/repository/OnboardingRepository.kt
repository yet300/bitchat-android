package com.app.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * First-run onboarding completion flag. Drives the root navigation choice between the onboarding
 * flow and the chats flow on launch. Permissionless: completing (or skipping through) onboarding is
 * the only gate — never a permission grant.
 */
interface OnboardingRepository {

    /**
     * Synchronous snapshot of whether onboarding has been completed. Used to pick the initial
     * navigation child at construction time without a splash flash — the backing settings read is
     * synchronous.
     */
    fun isCompleted(): Boolean

    fun observeCompleted(): Flow<Boolean>

    suspend fun setCompleted()
}
