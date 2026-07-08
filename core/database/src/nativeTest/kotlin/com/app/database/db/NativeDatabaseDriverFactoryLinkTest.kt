package com.app.database.db

import com.yet.sqlcipher.NativeSqlCipherDriverFactory
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Existence of this test forces `linkDebugTest*` to actually produce a native test binary, which is
 * the CI-side proof that the static SQLCipher bundled in sqlcipher-driver's klib resolves every
 * sqlite3_* symbol SQLiter needs (there is no simulator runtime in the gate — opening the DB under
 * a key is covered by the library's own on-simulator tests and the owner's manual on-device check).
 */
class NativeDatabaseDriverFactoryLinkTest {

    @Test
    fun factoryConstructs() {
        assertNotNull(NativeSqlCipherDriverFactory())
    }
}
