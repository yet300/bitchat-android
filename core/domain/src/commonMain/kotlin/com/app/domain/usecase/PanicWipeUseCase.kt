package com.app.domain.usecase

import com.app.domain.repository.ContactRepository
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.MediaCleaner
import com.app.domain.repository.MessageRepository
import com.app.domain.repository.MeshResetPort

/**
 * Panic wipe: clear all timelines/contacts/identity, rotate the live mesh identity,
 * and delete all stored media files so no trace survives process death.
 */
class PanicWipeUseCase(
    private val messages: MessageRepository,
    private val contacts: ContactRepository,
    private val identity: IdentityRepository,
    private val meshReset: MeshResetPort,
    private val mediaCleaner: MediaCleaner,
) {
    suspend operator fun invoke() {
        messages.clearAll()
        contacts.clearAll()
        identity.panicWipe()
        meshReset.reset()
        mediaCleaner.wipeMedia()
    }
}
