package com.app.database

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the database is actually encrypted at rest: a row written under one passphrase is readable
 * when reopened with the SAME passphrase, and reopening with a WRONG passphrase fails. Runs on a
 * device/emulator because SQLCipher's native libraries cannot load in host-JVM unit tests — so this is
 * deliberately NOT part of the unit-test gate (see FIX_BACKLOG §F).
 *
 * Uses explicit passphrases (not the Signum-derived one) to keep the encryption claim deterministic;
 * the Signum key path is verified separately at the app layer.
 */
@RunWith(AndroidJUnit4::class)
class SqlCipherEncryptionTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dbName = "enc_proof_test.db"

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        context.deleteDatabase(dbName)
    }

    private fun open(passphrase: ByteArray): BitMessageDatabase {
        val driver = AndroidSqliteDriver(
            schema = BitMessageDatabase.Schema,
            context = context,
            name = dbName,
            factory = SupportOpenHelperFactory(passphrase),
        )
        return BitMessageDatabase(driver)
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

        // Reopen with a wrong key — SQLCipher must reject it.
        try {
            open(bad).secureSettingQueries.selectByKey("nickname").executeAsOneOrNull()
            fail("Expected opening the encrypted database with a wrong passphrase to fail")
        } catch (expected: Exception) {
            assertTrue(true)
        }
    }
}
