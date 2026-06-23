package com.app.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.app.database.db.DatabaseDriverFactory
import java.util.Properties

/**
 * In-memory JDBC driver for host-JVM DAO tests — exercises the schema/queries without SQLCipher (whose
 * native libs cannot load on the host). The production encryption path is covered by the instrumented
 * device test.
 */
class TestDatabaseDriverFactory : DatabaseDriverFactory {
    override suspend fun create(): SqlDriver =
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), BitMessageDatabase.Schema)
}
