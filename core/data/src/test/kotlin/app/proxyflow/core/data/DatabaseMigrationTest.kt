package app.proxyflow.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.proxyflow.core.data.crypto.AesGcmCredentialCipher
import app.proxyflow.core.data.crypto.CredentialCodec
import app.proxyflow.core.data.db.MIGRATION_1_2
import app.proxyflow.core.data.db.MIGRATION_2_3
import app.proxyflow.core.data.db.ProxyFlowDatabase
import app.proxyflow.core.model.ProfileTest
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.testing.InMemoryActiveProfileStore
import app.proxyflow.core.testing.SoftwareSecretKeyStore
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The migration discipline (ADR-0009). Each earlier schema, as committed in
 * core/data/schemas, is created with data, migrated to the current version,
 * checked against the current schema and then read by the app's own database
 * and repository. That CI's freshly exported current schema equals the
 * committed file is checked by the CI schema step, not here: the build
 * rewrites that file before the tests run.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-${UUID.randomUUID()}.db"
    private val opened = mutableListOf<ProxyFlowDatabase>()

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ProxyFlowDatabase::class.java)

    @After
    fun closeAndDelete() {
        opened.forEach { it.close() }
        context.deleteDatabase(name)
    }

    private fun appDatabase() = ProxyFlowDatabase.create(context, name).also { opened += it }

    @Test
    fun version1ProfilesAndTheirCredentialsSurviveTheMigrations() = runTest {
        // Version 1 sealed each profile's credentials under one key named after the profile.
        val keys = SoftwareSecretKeyStore()
        val sealed = AesGcmCredentialCipher(keys, newKeyId = { "p1" })
            .seal("p1", CredentialCodec.encode("office-user".toCharArray(), "office-pass".toCharArray()))
        helper.createDatabase(name, 1).apply {
            execSQL(
                "INSERT INTO profiles (id, name, protocol, host, port, tls_mode, tls_server_name, requires_sign_in, " +
                    "credentials_ct, credentials_iv, credentials_format, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>("p1", "Office", "HTTP", "203.0.113.10", 3128, "None", null, 1, sealed.ciphertext, sealed.iv, 1, 10L, 20L),
            )
            execSQL(
                "INSERT INTO profiles (id, name, protocol, host, port, tls_mode, requires_sign_in, created_at, updated_at) " +
                    "VALUES ('p2', 'Home', 'SOCKS5', 'socks.example.net', 1080, 'Tls', 0, 30, 30)",
            )
            close()
        }

        // Room checks the migrated tables against the current schema.
        helper.runMigrationsAndValidate(name, CURRENT_VERSION, true, MIGRATION_1_2, MIGRATION_2_3).close()

        val repository = DefaultProfileRepository(
            RoomProfileStore(appDatabase().profileDao()),
            AesGcmCredentialCipher(keys),
            InMemoryActiveProfileStore(),
            cryptoDispatcher = UnconfinedTestDispatcher(),
        )
        assertEquals(listOf("Office", "Home"), repository.profiles.first().map { it.name })
        val read = repository.readCredentials("p1") as CredentialsRead.Available
        assertEquals("office-user", String(read.username))
        assertEquals("office-pass", String(read.password))
        assertEquals(CredentialsRead.NotRequired, repository.readCredentials("p2"))

        // Changing the password moves it to a key of its own and deletes the version 1 key.
        repository.save("p1", ProxyProfileInput.from(repository.get("p1")!!, "office-user").copy(password = "new-pass"))
        assertEquals("new-pass", String((repository.readCredentials("p1") as CredentialsRead.Available).password))
        assertTrue(AesGcmCredentialCipher.aliasFor("p1") !in keys.aliases())
    }

    /** Version 3 (M9) keeps each profile's last connection test; profiles from before have none, and nothing else changes. */
    @Test
    fun version2ProfilesKeepEverythingAndHaveNoTestYet() = runTest {
        helper.createDatabase(name, 2).apply {
            execSQL(
                "INSERT INTO profiles (id, name, protocol, host, port, tls_mode, requires_sign_in, created_at, updated_at) " +
                    "VALUES ('p1', 'Office', 'HTTP', '203.0.113.10', 3128, 'None', 0, 10, 20)",
            )
            close()
        }
        helper.runMigrationsAndValidate(name, CURRENT_VERSION, true, MIGRATION_2_3).close()

        val store = RoomProfileStore(appDatabase().profileDao())
        val record = store.get("p1")!!
        assertEquals("Office", record.name)
        assertEquals(20L, record.updatedAtMillis)
        assertNull("never tested", record.lastTest)

        // The test is written without touching the profile itself (its save time is how a result is matched to it).
        assertTrue(store.recordTest("p1", ProfileTest(30L, code = "AUTH_FAILED", latencyMillis = null)))
        assertEquals(ProfileTest(30L, code = "AUTH_FAILED", latencyMillis = null), store.get("p1")!!.lastTest)
        assertEquals(20L, store.get("p1")!!.updatedAtMillis)
        assertFalse("no such profile", store.recordTest("p404", ProfileTest(30L, code = null, latencyMillis = 5)))
    }

    @Test
    fun theCurrentSchemaOpensWithTheAppsDatabase() = runTest {
        // Created from the schema file; the app's database then compares it with its own entities (identity hash).
        helper.createDatabase(name, CURRENT_VERSION).close()
        assertEquals(emptySet<String>(), RoomProfileStore(appDatabase().profileDao()).ids())
    }

    @Test
    fun aDatabaseFromANewerVersionFailsLoudlyAndKeepsItsData() = runTest {
        helper.createDatabase(name, CURRENT_VERSION).apply {
            execSQL(
                "INSERT INTO profiles (id, name, protocol, host, port, tls_mode, requires_sign_in, created_at, updated_at) " +
                    "VALUES ('p1', 'Office', 'HTTP', '203.0.113.10', 3128, 'None', 0, 1, 1)",
            )
            // As if a newer app version had written it (for example after installing an older APK over it).
            version = CURRENT_VERSION + 1
            close()
        }

        val db = appDatabase()
        try {
            RoomProfileStore(db.profileDao()).ids()
            fail("opened a database without a migration path")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty(), "migration" in e.message.orEmpty().lowercase())
        }

        // Nothing was deleted.
        val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY)
        raw.rawQuery("SELECT name FROM profiles", null).use { rows ->
            assertTrue(rows.moveToFirst())
            assertEquals("Office", rows.getString(0))
        }
        raw.close()
    }

    private companion object {
        const val CURRENT_VERSION = 3
    }
}
