package com.example

import com.example.data.local.AuthType
import com.example.data.local.CommandSnippetDao
import com.example.data.local.CommandSnippetEntity
import com.example.data.local.FleetRepository
import com.example.data.local.ServerProfileDao
import com.example.data.local.ServerProfileEntity
import com.example.security.SecretCipher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FleetRepositorySecurityTest {

    private class FakeServerDao : ServerProfileDao {
        private val items = MutableStateFlow<List<ServerProfileEntity>>(emptyList())
        private var nextId = 1L

        override fun observeAllProfiles(): Flow<List<ServerProfileEntity>> = items
        override fun observeProfileById(id: Long): Flow<ServerProfileEntity?> =
            items.map { list -> list.firstOrNull { it.id == id } }

        override suspend fun getProfileById(id: Long): ServerProfileEntity? =
            items.value.firstOrNull { it.id == id }

        override suspend fun insertProfile(profile: ServerProfileEntity): Long {
            val id = nextId++
            items.value = items.value + profile.copy(id = id)
            return id
        }

        override suspend fun updateProfile(profile: ServerProfileEntity) {
            items.value = items.value.map { if (it.id == profile.id) profile else it }
        }

        override suspend fun updateHostFingerprint(id: Long, fingerprint: String) {
            items.value = items.value.map {
                if (it.id == id) it.copy(trustedHostFingerprint = fingerprint) else it
            }
        }

        override suspend fun clearHostFingerprint(id: Long) {
            items.value = items.value.map {
                if (it.id == id) it.copy(trustedHostFingerprint = null) else it
            }
        }

        override suspend fun updateConnectionTelemetry(id: Long, timestamp: Long, osSummary: String?) {
            items.value = items.value.map {
                if (it.id == id) it.copy(lastConnectedAt = timestamp, lastOsSummary = osSummary) else it
            }
        }

        override suspend fun deleteProfileById(id: Long) {
            items.value = items.value.filterNot { it.id == id }
        }
    }

    private class FakeSnippetDao : CommandSnippetDao {
        private val items = MutableStateFlow<List<CommandSnippetEntity>>(emptyList())
        override fun observeAllSnippets(): Flow<List<CommandSnippetEntity>> = items
        override suspend fun countSnippets(): Int = items.value.size
        override suspend fun insertSnippet(snippet: CommandSnippetEntity): Long {
            val id = (items.value.size + 1).toLong()
            items.value = items.value + snippet.copy(id = id)
            return id
        }
        override suspend fun insertAll(snippets: List<CommandSnippetEntity>) {
            items.value = items.value + snippets
        }
        override suspend fun deleteSnippetById(id: Long) {
            items.value = items.value.filterNot { it.id == id }
        }
    }

    @Test
    fun decryptCredentials_surfacesKeystoreFailureInsteadOfBlankFallback() = runTest {
        val failingCipher = object : SecretCipher {
            override fun encrypt(plaintext: String): String = "enc:$plaintext"
            override fun decrypt(encodedPayload: String): String {
                throw IllegalStateException("Keystore key permanently invalidated")
            }
        }
        val repo = FleetRepository(FakeServerDao(), FakeSnippetDao(), failingCipher)
        val profile = ServerProfileEntity(
            id = 1L,
            alias = "prod",
            host = "10.0.0.1",
            port = 22,
            username = "root",
            authType = AuthType.PASSWORD,
            encryptedSecret = "enc:secret123"
        )

        val result = repo.decryptCredentials(profile)
        assertNotNull(result.decryptionError)
        assertEquals("", result.secret)
    }

    @Test
    fun decryptCredentials_succeedsWithoutDecryptionError() = runTest {
        val okCipher = object : SecretCipher {
            override fun encrypt(plaintext: String): String = "enc:$plaintext"
            override fun decrypt(encodedPayload: String): String = encodedPayload.removePrefix("enc:")
        }
        val repo = FleetRepository(FakeServerDao(), FakeSnippetDao(), okCipher)
        val id = repo.saveServerProfile(
            alias = "prod",
            host = "10.0.0.1",
            port = 22,
            username = "root",
            authType = AuthType.PASSWORD,
            plainSecret = "hunter2",
            plainPassphrase = ""
        )
        val saved = repo.getProfile(id)!!
        val decrypted = repo.decryptCredentials(saved)
        assertNull(decrypted.decryptionError)
        assertEquals("hunter2", decrypted.secret)
    }
}
