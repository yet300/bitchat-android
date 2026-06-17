package com.yet.bitmessage.android.verification

import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.VerificationRepository
import com.app.transport.VerificationService

/**
 * Builds this device's verification payload via the graph-owned [VerificationService] (no crypto
 * reimplemented here). Nickname / npub come from the domain [IdentityRepository], the single source
 * of truth. QR encoding/rendering happens in the UI layer (qrose), so no platform QR dependency is
 * needed here.
 */
class VerificationRepositoryImpl(
    private val verificationService: VerificationService,
    private val identityRepository: IdentityRepository,
) : VerificationRepository {

    override suspend fun buildMyVerificationQr(): String? {
        val identity = identityRepository.myIdentity()
        return verificationService.buildMyQRString(identity.nickname, identity.nostrNpub)
    }
}
