package com.app.domain.usecase

import com.app.domain.repository.ContactRepository
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.MessageRepository

/**
 * Panic wipe: clear all timelines, all contacts (favorites/blocked) and the cryptographic
 * identity. Transport/crypto/file-system side effects are handled by the implementations.
 */
class PanicWipeUseCase(
    private val messages: MessageRepository,
    private val contacts: ContactRepository,
    private val identity: IdentityRepository,
) {
    suspend operator fun invoke() {
        messages.clearAll()
        contacts.clearAll()
        identity.panicWipe()
    }
}
