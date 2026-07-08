package com.app.database.db

import com.yet.sqlcipher.NativeSqlCipherDriverFactory

/**
 * Deletes the on-disk database created by the SQLCipher driver factory. Deletion goes through the
 * library (SQLiter path resolution, Application Support) — a hand-built path would silently miss
 * the file. Used by the panic wipe.
 */
fun deleteNativeDatabaseFile() {
    NativeSqlCipherDriverFactory().deleteDatabase(DB_FILE_NAME)
}
