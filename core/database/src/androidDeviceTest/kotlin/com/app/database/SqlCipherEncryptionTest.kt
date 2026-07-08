package com.app.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yet.sqlcipher.AndroidSqlCipherDriverFactory
import com.yet.sqlcipher.SqlCipherConfig
import com.yet.sqlcipher.SqlCipherRecovery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the database is actually encrypted at rest: a row written under one passphrase is readable
 * when reopened with the SAME passphrase, and reopening with a WRONG passphrase fails. Goes through
 * the sqlcipher-driver library (the exact production path, including its cipher_version probe) —
 * no direct net.zetetic usage. Runs on a device/emulator because SQLCipher's native libraries
 * cannot load in host-JVM unit tests — so this is deliberately NOT part of the unit-test gate.
 *
 * Uses explicit passphrases (not the KSafe-derived one) to keep the encryption claim deterministic;
 * the real key path is verified separately at the app layer.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherEncryptionTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dbName = "enc_proof_test.db"
    private val factory get() = AndroidSqlCipherDriverFactory(context)

    @Before
    fun setUp() {
        factory.deleteDatabase(dbName)
    }

    private fun open(passphrase: ByteArray): BitMessageDatabase = runBlocking {
        val driver = factory.create(
            schema = BitMessageDatabase.Schema,
            config = SqlCipherConfig(
                name = dbName,
                key = { passphrase.copyOf() },
                // Fail, not Recreate: a wrong key must surface as an error, or this test proves nothing.
                recovery = SqlCipherRecovery.Fail,
            ),
        )
        BitMessageDatabase(driver)
    }

    @Test
    fun rightKeyOpens_wrongKeyFails() {
        val good = "correct horse battery staple".encodeToByteArray()
        val bad = "totally wrong passphrase".encodeToByteArray()

        // Write a secret under the good key, then close.
        open(good).secureSettingQueries.upsert("nickname", "field-agent")

        // Reopen with the good key — the row is readable (and stayed encrypted on disk).
        assertEquals(
            "field-agent",
            open(good).secureSettingQueries.selectByKey("nickname").executeAsOneOrNull(),
        )

        // Reopen with a wrong key — SQLCipher must reject it inside create() (recovery = Fail).
        try {
            open(bad)
            fail("Expected opening the encrypted database with a wrong passphrase to fail")
        } catch (expected: Exception) {
            // expected
        }
    }
}
