package com.example.data.local

import com.example.security.SecretCipher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class FleetRepository(
    private val serverDao: ServerProfileDao,
    private val snippetDao: CommandSnippetDao,
    private val cipher: SecretCipher
) {
    val allProfiles: Flow<List<ServerProfileEntity>> = serverDao.observeAllProfiles()
    val allSnippets: Flow<List<CommandSnippetEntity>> = snippetDao.observeAllSnippets()

    fun observeProfile(id: Long): Flow<ServerProfileEntity?> = serverDao.observeProfileById(id)

    suspend fun getProfile(id: Long): ServerProfileEntity? = withContext(Dispatchers.IO) {
        serverDao.getProfileById(id)
    }

    suspend fun saveServerProfile(
        existingId: Long? = null,
        alias: String,
        host: String,
        port: Int,
        username: String,
        authType: AuthType,
        plainSecret: String,
        plainPassphrase: String
    ): Long = withContext(Dispatchers.IO) {
        val existing = existingId?.let { serverDao.getProfileById(it) }
        val encryptedSecret = if (plainSecret.isNotEmpty()) {
            cipher.encrypt(plainSecret)
        } else {
            existing?.encryptedSecret.orEmpty()
        }
        val encryptedPassphrase = if (authType == AuthType.PRIVATE_KEY) {
            if (plainPassphrase.isNotEmpty()) cipher.encrypt(plainPassphrase)
            else existing?.encryptedPassphrase.orEmpty()
        } else {
            ""
        }

        val entity = ServerProfileEntity(
            id = existingId ?: 0L,
            alias = alias.trim(),
            host = host.trim(),
            port = port,
            username = username.trim(),
            authType = authType,
            encryptedSecret = encryptedSecret,
            encryptedPassphrase = encryptedPassphrase,
            trustedHostFingerprint = if (existing != null && existing.host == host.trim() && existing.port == port) {
                existing.trustedHostFingerprint
            } else {
                null
            },
            lastOsSummary = existing?.lastOsSummary,
            lastConnectedAt = existing?.lastConnectedAt,
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )

        if (existingId != null && existingId > 0L) {
            serverDao.updateProfile(entity)
            existingId
        } else {
            serverDao.insertProfile(entity)
        }
    }

    suspend fun decryptCredentials(profile: ServerProfileEntity): DecryptedCredentials =
        withContext(Dispatchers.IO) {
            val secretResult = runCatching { cipher.decrypt(profile.encryptedSecret) }
            val passphraseResult = runCatching { cipher.decrypt(profile.encryptedPassphrase) }
            val err = secretResult.exceptionOrNull() ?: passphraseResult.exceptionOrNull()
            DecryptedCredentials(
                secret = secretResult.getOrDefault(""),
                passphrase = passphraseResult.getOrDefault(""),
                decryptionError = err?.let {
                    "Stored credentials could not be unlocked by Android Keystore. Please edit this server and re-enter them."
                }
            )
        }

    suspend fun saveTrustedFingerprint(serverId: Long, fingerprint: String) =
        withContext(Dispatchers.IO) {
            serverDao.updateHostFingerprint(serverId, fingerprint)
        }

    suspend fun resetTrustedFingerprint(serverId: Long) =
        withContext(Dispatchers.IO) {
            serverDao.clearHostFingerprint(serverId)
        }

    suspend fun recordSuccessfulConnection(serverId: Long, osSummary: String?) =
        withContext(Dispatchers.IO) {
            serverDao.updateConnectionTelemetry(
                id = serverId,
                timestamp = System.currentTimeMillis(),
                osSummary = osSummary
            )
        }

    suspend fun deleteServer(serverId: Long) = withContext(Dispatchers.IO) {
        serverDao.deleteProfileById(serverId)
    }

    suspend fun ensureDefaultSnippetsSeeded() = withContext(Dispatchers.IO) {
        if (snippetDao.countSnippets() == 0) {
            snippetDao.insertAll(defaultSnippets())
        }
    }

    suspend fun addCustomSnippet(title: String, command: String, category: String) =
        withContext(Dispatchers.IO) {
            snippetDao.insertSnippet(
                CommandSnippetEntity(
                    title = title.trim(),
                    command = command.trim(),
                    category = category.trim().ifEmpty { "Custom" },
                    isBuiltIn = false
                )
            )
        }

    suspend fun deleteSnippet(id: Long) = withContext(Dispatchers.IO) {
        snippetDao.deleteSnippetById(id)
    }

    private fun defaultSnippets(): List<CommandSnippetEntity> = listOf(
        CommandSnippetEntity(
            title = "Open Listening Ports",
            command = "ss -tulpn",
            category = "Network",
            isBuiltIn = true
        ),
        CommandSnippetEntity(
            title = "Firewall Status (UFW)",
            command = "sudo ufw status verbose",
            category = "Security",
            isBuiltIn = true
        ),
        CommandSnippetEntity(
            title = "Failed SSH Logins",
            command = "journalctl -u ssh -u sshd -n 40 --no-pager | grep -iE 'fail|invalid|error' || true",
            category = "Security",
            isBuiltIn = true
        ),
        CommandSnippetEntity(
            title = "Largest Directories",
            command = "du -xhd1 /var /home /opt 2>/dev/null | sort -hr | head -n 15",
            category = "Disk",
            isBuiltIn = true
        ),
        CommandSnippetEntity(
            title = "Docker Containers",
            command = "docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'",
            category = "Containers",
            isBuiltIn = true
        ),
        CommandSnippetEntity(
            title = "Nginx Config Test",
            command = "sudo nginx -t",
            category = "Web",
            isBuiltIn = true
        ),
        CommandSnippetEntity(
            title = "Failed Systemd Units",
            command = "systemctl --failed --no-pager",
            category = "System",
            isBuiltIn = true
        )
    )
}
