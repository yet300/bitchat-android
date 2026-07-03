package com.app.database.db

import co.touchlab.sqliter.DatabaseFileContext

/**
 * Deletes the on-disk database created by [NativeDatabaseDriverFactory]. SQLiter owns the default
 * database directory resolution (Application Support), so deletion must go through it too — a
 * hand-built path would silently miss the file. Used by the panic wipe.
 */
fun deleteNativeDatabaseFile() {
    DatabaseFileContext.deleteDatabase(DB_FILE_NAME, basePath = null)
}
