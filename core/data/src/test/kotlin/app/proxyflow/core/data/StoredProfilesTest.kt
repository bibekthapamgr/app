package app.proxyflow.core.data

import android.content.Context
import android.database.Cursor
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.proxyflow.core.data.crypto.AesGcmCredentialCipher
import app.proxyflow.core.data.db.ProxyFlowDatabase
import app.proxyflow.core.model.ProxyProfileInput
import app.proxyflow.core.model.ProxyProtocol
import app.proxyflow.core.model.TlsMode
import app.proxyflow.core.testing.InMemoryActiveProfileStore
import app.proxyflow.core.testing.SoftwareSecretKeyStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M3 acceptance on the real Room database (Robolectric's SQLite), with software
 * keys standing in for the Android Keystore. The emulator tests (androidTest)
 * repeat the plaintext and restart checks with Keystore keys.
 */
@RunWith(AndroidJUnit4::class)
class StoredProfilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "profiles-${UUID.randomUUID()}.db"

    // The Keystore keeps keys across app restarts; one software key store plays that part here.
    private val keys = SoftwareSecretKeyStore()
    private val active = InMemoryActiveProfileStore()
    private val opened = mutableListOf<ProxyFlowDatabase>()

    private val username = "alice-${UUID.randomUUID()}"
    private val password = "pw-${UUID.randomUUID()}"
    private val office = ProxyProfileInput(
        name = "Office",
        protocol = ProxyProtocol.HTTP,
        server = "203.0.113.10",
        port = "3128",
        requiresSignIn = true,
        username = username,
        password = password,
    )

    /** A fresh database object and repository on the same file, like a new app process. */
    private fun startApp(): DefaultProfileRepository {
        val db = ProxyFlowDatabase.create(context, dbName).also { opened += it }
        return DefaultProfileRepository(
            RoomProfileStore(db.profileDao()),
            AesGcmCredentialCipher(keys),
            active,
            cryptoDispatcher = UnconfinedTestDispatcher(),
        )
    }

    @After
    fun closeAndDelete() {
        opened.forEach { it.close() }
        context.deleteDatabase(dbName)
    }

    @Test
    fun profilesSurviveAnAppRestart() = runTest {
        startApp().run {
            save(null, office)
            save(null, ProxyProfileInput(name = "Home", server = "socks.example.net", port = "1080", useTls = true))
            save(null, office.copy(name = "Backup", username = "backup", password = "backup-pw"))
        }
        opened.forEach { it.close() }

        val restarted = startApp()
        val profiles = restarted.profiles.first()
        assertEquals(listOf("Office", "Home", "Backup"), profiles.map { it.name })
        assertEquals(TlsMode.Tls, profiles[1].tlsMode)
        val credentials = restarted.readCredentials(profiles[0].id) as CredentialsRead.Available
        assertEquals(username, String(credentials.username))
        assertEquals(password, String(credentials.password))
        assertEquals("backup-pw", String((restarted.readCredentials(profiles[2].id) as CredentialsRead.Available).password))
    }

    @Test
    fun noCredentialIsInTheDatabaseFilesOrAnExportOfIt() = runTest {
        val repository = startApp()
        val id = repository.save(null, office).id
        repository.save(id, office.copy(name = "Office 2", password = ""))
        repository.duplicate(id, "Office (copy)")

        // An export: every column of every row of every table, as text and as bytes.
        val export = StringBuilder()
        val db = opened.single().openHelper.readableDatabase
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { tables ->
            while (tables.moveToNext()) {
                db.query("SELECT * FROM \"${tables.getString(0)}\"").use { rows ->
                    while (rows.moveToNext()) {
                        for (column in 0 until rows.columnCount) {
                            when (rows.getType(column)) {
                                Cursor.FIELD_TYPE_BLOB -> export.append(rows.getBlob(column).decodeToString())
                                Cursor.FIELD_TYPE_NULL -> Unit
                                else -> export.append(rows.getString(column))
                            }
                            export.append('|')
                        }
                    }
                }
            }
        }
        assertTrue("the export has the profile", "203.0.113.10" in export)
        assertFalse(username in export)
        assertFalse(password in export)

        // The files themselves, including the write-ahead log, in the encodings SQLite and Java use.
        opened.forEach { it.close() }
        val files = context.getDatabasePath(dbName).parentFile!!.listFiles { f -> f.name.startsWith(dbName) }!!.toList()
        assertTrue(files.isNotEmpty())
        files.forEach { file -> assertNoSecret(file) }
    }

    @Test
    fun deletingAProfileLeavesNothingThatCanBeDecrypted() = runTest {
        val repository = startApp()
        val id = repository.save(null, office).id
        val kept = repository.save(null, office.copy(name = "Kept", username = "other", password = "other-pw")).id
        val sealed = RoomProfileStore(opened.single().profileDao()).get(id)!!.credentials!!

        repository.delete(id)
        assertNull(repository.get(id))
        assertFalse(AesGcmCredentialCipher.aliasFor(id) in keys.aliases())
        assertTrue(runCatching { AesGcmCredentialCipher(keys).open(id, sealed) }.isFailure)
        assertEquals("other-pw", String((repository.readCredentials(kept) as CredentialsRead.Available).password))
    }

    @Test
    fun theActiveProfileIsKeptInTheSettingsFile() = runTest {
        val file = File(context.filesDir, "settings-${UUID.randomUUID()}.preferences_pb")
        val store = DataStoreActiveProfileStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
        assertNull(store.activeId.first())
        store.set("p2")
        assertEquals("p2", store.activeId.first())
        store.set(null)
        assertNull(store.activeId.first())
        assertTrue(file.exists())
    }

    private fun assertNoSecret(file: File) {
        val bytes = file.readBytes()
        for (secret in listOf(username, password)) {
            for (charset in listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)) {
                assertFalse("$secret in ${file.name} ($charset)", bytes.contains(secret.toByteArray(charset)))
            }
        }
    }

    private fun ByteArray.contains(slice: ByteArray): Boolean =
        (0..size - slice.size).any { start -> slice.indices.all { this[start + it] == slice[it] } }
}
