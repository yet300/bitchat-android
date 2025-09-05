package com.bitchat.domain.repository

import com.bitchat.domain.model.Identity
import com.bitchat.domain.model.NoiseKeys
import com.bitchat.domain.model.SigningKeys

interface IdentityRepository {
    suspend fun generateIdentity(): Result<Identity>
    suspend fun getCurrentIdentity(): Identity?
    suspend fun saveIdentity(identity: Identity): Result<Unit>
    suspend fun deleteIdentity(): Result<Unit>
    suspend fun getPeerId(): String
    suspend fun getNoiseKeys(): NoiseKeys?
    suspend fun getSigningKeys(): SigningKeys?
    suspend fun verifyIdentity(identity: Identity): Boolean
}