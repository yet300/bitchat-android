package com.app.domain.usecase

import com.app.domain.repository.ContactRepository
import com.app.domain.repository.DatabasePanicWiper
import com.app.domain.repository.IdentityRepository
import com.app.domain.repository.MediaCleaner
import com.app.domain.repository.MessageRepository
import com.app.domain.repository.MeshResetPort

/**
 * Panic wipe: clear all timelines/contacts/identity, rotate the live mesh identity, delete all stored
 * media, and crypto-erase the encrypted database (destroy its passphrase + drop the file) so no trace
 * survives process death.
 */
class PanicWipeUseCase(
    private val messages: MessageRepository,
    private val contacts: ContactRepository,
    private val identity: IdentityRepository,
    private val meshReset: MeshResetPort,
    private val mediaCleaner: MediaCleaner,
    private val databaseWiper: DatabasePanicWiper,
) {
    suspend operator fun invoke() {
        messages.clearAll()
        contacts.clearAll()
        identity.panicWipe()
        meshReset.reset()
        mediaCleaner.wipeMedia()
        // Last: crypto-erase the DB. Destroying the passphrase makes everything written above (and any
        // history) permanently unreadable even if the file deletion is interrupted.
        databaseWiper.wipe()
    }
}
