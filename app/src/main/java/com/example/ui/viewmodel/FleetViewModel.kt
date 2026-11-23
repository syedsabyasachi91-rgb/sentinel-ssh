package com.example.ui.viewmodel

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AuthType
import com.example.data.local.FleetRepository
import com.example.data.local.ServerProfileEntity
import com.example.ssh.LinuxTelemetryParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ServerFormState(
    val isOpen: Boolean = false,
    val editingServerId: Long? = null,
    val alias: String = "",
    val host: String = "",
    val port: String = "22",
    val username: String = "root",
    val authType: AuthType = AuthType.PASSWORD,
    val secret: String = "",
    val passphrase: String = "",
    val hasExistingSecret: Boolean = false,
    val importedKeyName: String? = null,
    val errorMessage: String? = null,
    val isSaving: Boolean = false
)

class FleetViewModel(
    private val repository: FleetRepository,
    private val contentResolver: ContentResolver
) : ViewModel() {

    val servers: StateFlow<List<ServerProfileEntity>> = repository.allProfiles
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val _formState = MutableStateFlow(ServerFormState())
    val formState: StateFlow<ServerFormState> = _formState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.ensureDefaultSnippetsSeeded()
        }
    }

    fun openAddServerForm() {
        _formState.value = ServerFormState(isOpen = true)
    }

    fun openEditServerForm(profile: ServerProfileEntity) {
        _formState.value = ServerFormState(
            isOpen = true,
            editingServerId = profile.id,
            alias = profile.alias,
            host = profile.host,
            port = profile.port.toString(),
            username = profile.username,
            authType = profile.authType,
            hasExistingSecret = profile.encryptedSecret.isNotEmpty()
        )
    }

    fun closeForm() {
        _formState.value = ServerFormState(isOpen = false)
    }

    fun updateAlias(value: String) = _formState.update { it.copy(alias = value, errorMessage = null) }
    fun updateHost(value: String) = _formState.update { it.copy(host = value, errorMessage = null) }
    fun updatePort(value: String) = _formState.update {
        it.copy(port = value.filter { ch -> ch.isDigit() }.take(5), errorMessage = null)
    }
    fun updateUsername(value: String) = _formState.update { it.copy(username = value, errorMessage = null) }
    fun updateAuthType(authType: AuthType) = _formState.update {
        it.copy(authType = authType, secret = "", passphrase = "", importedKeyName = null, errorMessage = null)
    }
    fun updateSecret(value: String) = _formState.update { it.copy(secret = value, errorMessage = null) }
    fun updatePassphrase(value: String) = _formState.update { it.copy(passphrase = value, errorMessage = null) }

    fun importPrivateKeyFromUri(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        val bytes = stream.readBytes()
                        if (bytes.size > MAX_KEY_FILE_BYTES) {
                            throw IllegalArgumentException("Key file exceeds 128 KB limit")
                        }
                        String(bytes, Charsets.UTF_8).trim()
                    } ?: throw IllegalStateException("Unable to read selected key file")
                }
            }
            result.onSuccess { keyText ->
                _formState.update {
                    it.copy(
                        authType = AuthType.PRIVATE_KEY,
                        secret = keyText,
                        importedKeyName = uri.lastPathSegment?.substringAfterLast('/') ?: "Imported key",
                        errorMessage = null
                    )
                }
            }.onFailure { err ->
                _formState.update {
                    it.copy(errorMessage = err.message ?: "Failed to import key file")
                }
            }
        }
    }

    fun saveServer(onSaved: (Long) -> Unit = {}) {
        val current = _formState.value
        val hostTrimmed = LinuxTelemetryParser.normalizeHostInput(current.host)
        val userTrimmed = current.username.trim()
        val parsedPort = current.port.toIntOrNull()
        val sanitizedSecret = if (current.authType == AuthType.PASSWORD) {
            current.secret.replace("\r", "").replace("\n", "")
        } else {
            current.secret
        }

        val validationError = when {
            hostTrimmed.isEmpty() -> "Hostname or IP address is required."
            hostTrimmed.any { it.isWhitespace() } -> "Hostname or IP address cannot contain spaces."
            parsedPort == null || parsedPort !in 1..65535 -> "Port must be between 1 and 65535."
            userTrimmed.isEmpty() -> "SSH username is required."
            sanitizedSecret.isEmpty() && !current.hasExistingSecret -> {
                if (current.authType == AuthType.PASSWORD) "SSH password is required."
                else "SSH private key is required."
            }
            current.authType == AuthType.PRIVATE_KEY && sanitizedSecret.isNotEmpty() -> {
                LinuxTelemetryParser.validatePrivateKeyFormat(sanitizedSecret)
            }
            else -> null
        }

        if (validationError != null) {
            _formState.update { it.copy(errorMessage = validationError) }
            return
        }

        val resolvedAlias = current.alias.trim().ifEmpty { hostTrimmed }
        _formState.update { it.copy(isSaving = true, errorMessage = null) }

        viewModelScope.launch {
            runCatching {
                repository.saveServerProfile(
                    existingId = current.editingServerId,
                    alias = resolvedAlias,
                    host = hostTrimmed,
                    port = parsedPort!!,
                    username = userTrimmed,
                    authType = current.authType,
                    plainSecret = sanitizedSecret,
                    plainPassphrase = current.passphrase
                )
            }.onSuccess { savedId ->
                _formState.value = ServerFormState(isOpen = false)
                onSaved(savedId)
            }.onFailure { err ->
                _formState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = err.message ?: "Could not save encrypted profile."
                    )
                }
            }
        }
    }

    fun deleteServer(serverId: Long) {
        viewModelScope.launch {
            repository.deleteServer(serverId)
        }
    }

    fun resetTrustedHostKey(serverId: Long) {
        viewModelScope.launch {
            repository.resetTrustedFingerprint(serverId)
        }
    }

    companion object {
        private const val MAX_KEY_FILE_BYTES = 128 * 1024
    }
}
