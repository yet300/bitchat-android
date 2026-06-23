package com.app.domain.repository

/**
 * Panic crypto-erase for the encrypted database. Destroying the passphrase (its only stored copy is
 * behind the hardware-rooted secret store via [DatabaseKeyProvider]) renders the whole database
 * permanently undecryptable — stronger than file deletion; the file is then dropped as well.
 */
interface DatabasePanicWiper {

    /** Close the database, destroy its passphrase (crypto-erase), and delete the database file. */
    suspend fun wipe()
}
