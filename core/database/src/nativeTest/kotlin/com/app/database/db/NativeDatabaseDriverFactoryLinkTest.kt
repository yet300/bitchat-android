package com.app.database.db

import com.app.domain.repository.DatabaseKeyProvider
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Existence of this test forces `linkDebugTest*` to actually produce a native test binary, which is
 * the CI-side proof that the vendored SQLCipher framework resolves every sqlite3_* symbol SQLiter
 * needs (there is no simulator runtime in the gate — opening the DB under a key is the owner's
 * manual on-device check).
 */
class NativeDatabaseDriverFactoryLinkTest {

    @Test
    fun factoryConstructs() {
        val factory = NativeDatabaseDriverFactory(
            keyProvider = object : DatabaseKeyProvider {
                override suspend fun passphrase(): ByteArray = ByteArray(32) { it.toByte() }
                override suspend fun destroyKey() = Unit
            },
        )
        assertNotNull(factory)
    }
}
