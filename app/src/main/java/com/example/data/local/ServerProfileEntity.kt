package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AuthType {
    PASSWORD,
    PRIVATE_KEY
}

@Entity(tableName = "server_profiles")
data class ServerProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val alias: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: AuthType,
    val encryptedSecret: String,
    val encryptedPassphrase: String = "",
    val trustedHostFingerprint: String? = null,
    val lastOsSummary: String? = null,
    val lastConnectedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class DecryptedCredentials(
    val secret: String,
    val passphrase: String,
    val decryptionError: String? = null
)
