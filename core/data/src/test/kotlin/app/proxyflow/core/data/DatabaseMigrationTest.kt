package app.proxyflow.core.data

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.proxyflow.core.data.db.ProxyFlowDatabase
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The migration discipline (ADR-0009). Version 1 is the first schema, so there
 * is no migration yet; these tests make sure the committed schema is the one the
 * code builds, and that a database the code can't handle is never wiped. Each
 * future version adds a `migrate1To2`-style test here, built on the same helper.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-${UUID.randomUUID()}.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ProxyFlowDatabase::class.java)

    @Test
    fun theCommittedVersion1SchemaIsTheOneTheCodeBuilds() {
        // Creates the database from core/data/schemas/…/1.json, then has Room check it against the entities.
        helper.createDatabase(name, 1).close()
        helper.runMigrationsAndValidate(name, 1, true).close()
        context.deleteDatabase(name)
    }

    @Test
    fun aDatabaseFromANewerVersionFailsLoudlyAndKeepsItsData() = runTest {
        helper.createDatabase(name, 1).apply {
            execSQL(
                "INSERT INTO profiles (id, name, protocol, host, port, tls_mode, requires_sign_in, created_at, updated_at) " +
                    "VALUES ('p1', 'Office', 'HTTP', '203.0.113.10', 3128, 'None', 0, 1, 1)",
            )
            // As if a newer app version had written it (for example after installing an older APK over it).
            version = 2
            close()
        }

        val db = ProxyFlowDatabase.create(context, name)
        try {
            RoomProfileStore(db.profileDao()).ids()
            fail("opened a database without a migration path")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty(), "migration" in e.message.orEmpty().lowercase())
        } finally {
            db.close()
        }

        // Nothing was deleted.
        val raw = android.database.sqlite.SQLiteDatabase.openDatabase(
            context.getDatabasePath(name).path,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        )
        raw.rawQuery("SELECT name FROM profiles", null).use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("Office", rows.getString(0))
        }
        raw.close()
        context.deleteDatabase(name)
    }
}
