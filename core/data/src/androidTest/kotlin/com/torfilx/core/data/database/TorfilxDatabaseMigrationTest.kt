package com.torfilx.core.data.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the database against the classic "first migration crashes every device" bug.
 *
 * Destructive fallback is deliberately off, so once the schema version is bumped Room refuses to
 * open an old database unless a migration is supplied — an unrecoverable startup crash if one is
 * forgotten. This replays the exported schemas to prove every version opens.
 *
 * When you add a schema change:
 *  1. bump `TorfilxDatabase.version`;
 *  2. write a `Migration(N, N+1)` and pass it to the Room builder in `DataModule`;
 *  3. add a `runMigrationsAndValidate(TEST_DB, N+1, true, MIGRATION_N_N1)` line below.
 *
 * Instrumented (needs a device/emulator); wiring it into CI is tracked as prod-readiness item #14.
 */
@RunWith(AndroidJUnit4::class)
class TorfilxDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        TorfilxDatabase::class.java,
    )

    @Test
    fun allExportedSchemasOpen() {
        // Create the earliest schema from its exported JSON.
        helper.createDatabase(TEST_DB, 1).close()

        // As migrations are added, validate the chain here, e.g.:
        // helper.runMigrationsAndValidate(TEST_DB, 2, true, MIGRATION_1_2)

        // Opening the real database exercises the current schema against the created one.
        Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            TorfilxDatabase::class.java,
            TEST_DB,
        ).build().apply {
            openHelper.writableDatabase
            close()
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
