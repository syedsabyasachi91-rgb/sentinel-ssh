package com.example.ssh

import android.util.Base64
import com.example.data.local.AuthType
import com.example.data.local.DecryptedCredentials
import com.example.data.local.ServerProfileEntity
import com.example.domain.model.LogEntry
import com.example.domain.model.LogSeverity
import com.example.domain.model.ServiceAction
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Logger
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.Security
import java.util.Properties

data class CommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int
)

interface SshShellHandle {
    val outputFlow: Flow<String>
    suspend fun sendText(text: String)
    suspend fun sendBytes(bytes: ByteArray)
    suspend fun resizePty(cols: Int, rows: Int)
    fun close()
}

class HostKeyRejectedException(
    message: String,
    val technicalDetails: String? = null
) : Exception(message)

class SshAuthenticationException(
    message: String,
    val technicalDetails: String? = null,
    cause: Throwable? = null
) : Exception(message, cause)

class SshConnectionException(
    message: String,
    val technicalDetails: String? = null,
    cause: Throwable? = null
) : Exception(message, cause)

class SudoPasswordRequiredException(message: String) : Exception(message)

class IncorrectSudoPasswordException(message: String) : Exception(message)

internal object SshCryptoProvider {
    private val lock = Any()

    @Volatile
    private var initialized = false

    fun ensureBouncyCastleProvider() {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            runCatching {
                val existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
                if (existing == null || existing.javaClass.name != BouncyCastleProvider::class.java.name) {
                    Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                    Security.addProvider(BouncyCastleProvider())
                }
            }
            initialized = true
        }
    }
}

internal class SshCredentialUserInfo(
    private val authType: AuthType,
    private val secret: String,
    private val passphrase: String
) : UserInfo, UIKeyboardInteractive {
    private var keyboardInteractiveSubmitted = false

    override fun getPassphrase(): String? =
        if (authType == AuthType.PRIVATE_KEY) passphrase.ifEmpty { null } else null

    override fun getPassword(): String? =
        if (authType == AuthType.PASSWORD) secret.ifEmpty { null } else null

    override fun promptPassword(message: String?): Boolean =
        authType == AuthType.PASSWORD && secret.isNotEmpty()

    override fun promptPassphrase(message: String?): Boolean =
        authType == AuthType.PRIVATE_KEY && passphrase.isNotEmpty()

    override fun promptYesNo(message: String?): Boolean = false

    override fun showMessage(message: String?) = Unit

    override fun promptKeyboardInteractive(
        destination: String?,
        name: String?,
        instruction: String?,
        prompt: Array<out String>?,
        echo: BooleanArray?
    ): Array<String>? {
        if (authType != AuthType.PASSWORD || secret.isEmpty()) return null
        val prompts = prompt ?: return null
        // RFC 4256 allows 0-prompt info/banner packets before the actual password prompt.
        if (prompts.isEmpty()) return emptyArray()
        val echoFlags = echo ?: return null
        if (prompts.size != 1 || echoFlags.isEmpty() || echoFlags[0]) return null
        if (keyboardInteractiveSubmitted) return null
        keyboardInteractiveSubmitted = true
        return arrayOf(secret)
    }
}

internal object SshHandshakeLogger : Logger {
    private const val MAX_CAPTURED_LINES = 40
    private val activeBuffer = ThreadLocal<MutableList<String>?>()

    @Volatile
    private var installed = false

    fun installIfNeeded() {
        if (!installed) {
            synchronized(this) {
                if (!installed) {
                    JSch.setLogger(this)
                    installed = true
                }
            }
        }
    }

    fun startCapture(): MutableList<String> {
        installIfNeeded()
        val lines = mutableListOf<String>()
        activeBuffer.set(lines)
        return lines
    }

    fun stopCapture() {
        activeBuffer.remove()
    }

    override fun isEnabled(level: Int): Boolean {
        return activeBuffer.get() != null && level >= Logger.INFO
    }

    override fun log(level: Int, message: String?) {
        val buf = activeBuffer.get() ?: return
        val text = message?.trim().orEmpty()
        if (text.isEmpty()) return
        if (buf.size < MAX_CAPTURED_LINES) {
            buf.add(text)
        }
    }
}

internal fun classifySshConnectionException(
    error: Throwable,
    host: String,
    port: Int,
    username: String,
    authType: AuthType,
    hostKeyRejectedByUser: Boolean,
    hostKeyCheckFailed: Boolean,
    handshakeLogs: List<String>
): Exception {
    val causeChain = generateSequence(error) { it.cause }.toList()
    val rawMessage = error.message.orEmpty()
    val offeredMethodsLine = handshakeLogs.lastOrNull {
        it.contains("Authentications that can continue", ignoreCase = true)
    } ?: handshakeLogs.lastOrNull {
        it.contains("Authentication methods", ignoreCase = true)
    }
    val relevantLogs = handshakeLogs.filter { line ->
        line.contains("kex:", ignoreCase = true) ||
            line.contains("Authentications that can continue", ignoreCase = true) ||
            line.contains("Next authentication method", ignoreCase = true) ||
            line.contains("Server host key", ignoreCase = true) ||
            line.contains("Disconnecting", ignoreCase = true) ||
            line.contains("fail", ignoreCase = true) ||
            line.contains("error", ignoreCase = true)
    }.takeLast(6)

    val causeSummary = causeChain.joinToString(" -> ") {
        val name = it::class.java.simpleName.ifEmpty { "Exception" }
        val msg = it.message?.trim().orEmpty()
        if (msg.isNotEmpty()) "$name: $msg" else name
    }
    val technicalDetails = buildString {
        appendLine("Target: $username@$host:$port (${authType.name})")
        appendLine("Failure: $causeSummary")
        if (relevantLogs.isNotEmpty()) {
            appendLine("Handshake trace:")
            relevantLogs.forEach { appendLine("  • $it") }
        }
    }.trim()

    if (hostKeyRejectedByUser) {
        return HostKeyRejectedException("SSH host key verification was declined.", technicalDetails)
    }
    if (hostKeyCheckFailed ||
        rawMessage.contains("HostKey", ignoreCase = true) ||
        rawMessage.contains("UnknownHostKey", ignoreCase = true)
    ) {
        return HostKeyRejectedException("SSH host key verification failed for $host:$port.", technicalDetails)
    }

    val unknownHost = causeChain.filterIsInstance<UnknownHostException>().firstOrNull()
    if (unknownHost != null) {
        return SshConnectionException(
            "Could not resolve hostname '$host'. Check the IP or domain name and internet connectivity.",
            technicalDetails,
            error
        )
    }

    val refused = causeChain.filterIsInstance<ConnectException>().firstOrNull()
    if (refused != null || rawMessage.contains("ECONNREFUSED", ignoreCase = true)) {
        return SshConnectionException(
            "Connection refused by $host:$port. Verify sshd is running, port $port is open, and firewall rules allow access.",
            technicalDetails,
            error
        )
    }

    val timedOut = causeChain.filterIsInstance<SocketTimeoutException>().firstOrNull()
    if (timedOut != null || rawMessage.contains("timeout", ignoreCase = true) || rawMessage.contains("timed out", ignoreCase = true)) {
        return SshConnectionException(
            "Timed out connecting to $host:$port. Check the host address, port $port, and cloud/VPS firewall.",
            technicalDetails,
            error
        )
    }

    val noRoute = causeChain.filterIsInstance<NoRouteToHostException>().firstOrNull()
    if (noRoute != null || rawMessage.contains("ENETUNREACH", ignoreCase = true) || rawMessage.contains("EHOSTUNREACH", ignoreCase = true)) {
        return SshConnectionException(
            "Network unreachable for $host:$port. Verify your internet or VPN connection.",
            technicalDetails,
            error
        )
    }

    if (rawMessage.contains("Algorithm negotiation fail", ignoreCase = true)) {
        return SshConnectionException(
            "SSH algorithm negotiation failed with $host:$port. Server key exchange or host-key policy did not match.",
            technicalDetails,
            error
        )
    }

    if (rawMessage.contains("End of IO Stream Read", ignoreCase = true)) {
        return SshConnectionException(
            "The SSH server at $host:$port closed the connection during handshake (possible fail2ban block or firewall drop).",
            technicalDetails,
            error
        )
    }

    if (rawMessage.contains("Auth fail", ignoreCase = true) ||
        rawMessage.contains("Auth cancel", ignoreCase = true) ||
        rawMessage.contains("USERAUTH fail", ignoreCase = true)
    ) {
        val methodsText = offeredMethodsLine ?: rawMessage
        val onlyPublicKey = authType == AuthType.PASSWORD &&
            methodsText.contains("publickey", ignoreCase = true) &&
            !methodsText.contains("password", ignoreCase = true) &&
            !methodsText.contains("keyboard-interactive", ignoreCase = true)

        val userMessage = if (onlyPublicKey) {
            "Server at $host only accepts public key authentication (password login is disabled on this VPS)."
        } else {
            "Authentication rejected for $username@$host. Verify your username, password, and server SSH login policy."
        }
        return SshAuthenticationException(userMessage, technicalDetails, error)
    }

    return SshConnectionException(
        rawMessage.takeIf { it.isNotBlank() } ?: "Unable to connect to $host:$port.",
        technicalDetails,
        error
    )
}

class SshSessionManager {

    private var session: Session? = null
    private val sessionLock = Any()

    val isConnected: Boolean
        get() = session?.isConnected == true

    suspend fun connect(
        profile: ServerProfileEntity,
        credentials: DecryptedCredentials,
        onVerifyHostKey: suspend (fingerprintSha256: String, isChanged: Boolean) -> Boolean,
        onFingerprintTrusted: suspend (fingerprintSha256: String) -> Unit
    ) = withContext(Dispatchers.IO) {
        disconnect()
        SshCryptoProvider.ensureBouncyCastleProvider()

        val cleanHost = LinuxTelemetryParser.normalizeHostInput(profile.host)
        val cleanUser = profile.username.trim()
        val effectiveSecret = if (profile.authType == AuthType.PASSWORD) {
            credentials.secret.replace("\r", "").replace("\n", "")
        } else {
            credentials.secret
        }

        val jsch = JSch()
        if (profile.authType == AuthType.PRIVATE_KEY) {
            val keyBytes = effectiveSecret.toByteArray(Charsets.UTF_8)
            val passBytes = credentials.passphrase
                .takeIf { it.isNotEmpty() }
                ?.toByteArray(Charsets.UTF_8)
            try {
                jsch.addIdentity("sentinel_key_${profile.id}", keyBytes, null, passBytes)
            } finally {
                keyBytes.fill(0)
                passBytes?.fill(0)
            }
        }

        val newSession = jsch.getSession(cleanUser, cleanHost, profile.port)
        if (profile.authType == AuthType.PASSWORD) {
            val passwordBytes = effectiveSecret.toByteArray(Charsets.UTF_8)
            try {
                newSession.setPassword(passwordBytes)
            } finally {
                passwordBytes.fill(0)
            }
        }

        val config = Properties().apply {
            put("StrictHostKeyChecking", "yes")
            put(
                "server_host_key",
                "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256,ssh-rsa"
            )
            put(
                "PubkeyAcceptedAlgorithms",
                "ssh-ed25519,ecdsa-sha2-nistp256,ecdsa-sha2-nistp384,ecdsa-sha2-nistp521,rsa-sha2-512,rsa-sha2-256,ssh-rsa"
            )
            put(
                "PreferredAuthentications",
                if (profile.authType == AuthType.PRIVATE_KEY) {
                    "publickey,keyboard-interactive,password"
                } else {
                    "password,keyboard-interactive"
                }
            )
        }
        newSession.setConfig(config)
        newSession.timeout = CONNECT_TIMEOUT_MS

        var pendingTrustedFingerprint: String? = null
        var hostKeyRejectedByUser = false
        var hostKeyCheckFailed = false

        val tofuRepository = object : HostKeyRepository {
            override fun check(host: String?, key: ByteArray?): Int {
                if (key == null) {
                    hostKeyCheckFailed = true
                    return HostKeyRepository.NOT_INCLUDED
                }
                val sha256 = computeSha256Fingerprint(key)
                val saved = profile.trustedHostFingerprint
                if (saved != null && saved == sha256) {
                    return HostKeyRepository.OK
                }
                val isChanged = saved != null && saved != sha256
                val decision = runCatching {
                    kotlinx.coroutines.runBlocking {
                        onVerifyHostKey(sha256, isChanged)
                    }
                }.getOrDefault(false)
                return if (decision) {
                    pendingTrustedFingerprint = sha256
                    HostKeyRepository.OK
                } else {
                    hostKeyRejectedByUser = true
                    HostKeyRepository.NOT_INCLUDED
                }
            }

            override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
            override fun remove(host: String?, type: String?) = Unit
            override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
            override fun getKnownHostsRepositoryID(): String = "sentinel-tofu"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        }
        jsch.hostKeyRepository = tofuRepository

        newSession.userInfo = SshCredentialUserInfo(
            authType = profile.authType,
            secret = effectiveSecret,
            passphrase = credentials.passphrase
        )

        val handshakeLogs = SshHandshakeLogger.startCapture()
        try {
            newSession.connect(CONNECT_TIMEOUT_MS)
            newSession.serverAliveInterval = 15_000
            newSession.serverAliveCountMax = 3
            synchronized(sessionLock) {
                session = newSession
            }
            pendingTrustedFingerprint?.let { fp ->
                onFingerprintTrusted(fp)
            }
        } catch (e: Exception) {
            runCatching { newSession.disconnect() }
            throw classifySshConnectionException(
                error = e,
                host = cleanHost,
                port = profile.port,
                username = cleanUser,
                authType = profile.authType,
                hostKeyRejectedByUser = hostKeyRejectedByUser,
                hostKeyCheckFailed = hostKeyCheckFailed,
                handshakeLogs = handshakeLogs.toList()
            )
        } finally {
            SshHandshakeLogger.stopCapture()
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000
    }

    suspend fun executeCommand(
        command: String,
        timeoutMs: Int = 12_000,
        stdinPayload: String? = null
    ): CommandResult = withContext(Dispatchers.IO) {
        val activeSession = synchronized(sessionLock) { session }
            ?: throw IllegalStateException("Not connected to VPS")
        if (!activeSession.isConnected) {
            throw IllegalStateException("SSH session is closed")
        }

        val channel = activeSession.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val stdoutBuffer = ByteArrayOutputStream()
        val stderrBuffer = ByteArrayOutputStream()
        channel.setErrStream(stderrBuffer)
        val inputStream = channel.inputStream
        val outputStream = if (stdinPayload != null) channel.outputStream else null

        val stdinBytes = stdinPayload?.let { "$it\n".toByteArray(Charsets.UTF_8) }
        try {
            channel.connect(timeoutMs)
            if (outputStream != null && stdinBytes != null) {
                outputStream.write(stdinBytes)
                outputStream.flush()
                runCatching { outputStream.close() }
            }

            val buffer = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (currentCoroutineContext().isActive) {
                while (inputStream.available() > 0) {
                    val read = inputStream.read(buffer, 0, buffer.size)
                    if (read < 0) break
                    stdoutBuffer.write(buffer, 0, read)
                }
                if (channel.isClosed) {
                    if (inputStream.available() > 0) continue
                    break
                }
                if (System.currentTimeMillis() > deadline) {
                    throw IllegalStateException("Command timed out")
                }
                delay(30)
            }
            CommandResult(
                stdout = stdoutBuffer.toString(Charsets.UTF_8.name()),
                stderr = stderrBuffer.toString(Charsets.UTF_8.name()),
                exitCode = channel.exitStatus
            )
        } finally {
            stdinBytes?.fill(0)
            runCatching { channel.disconnect() }
        }
    }

    suspend fun controlSystemdService(
        unitName: String,
        action: ServiceAction,
        sudoPassword: String? = null
    ): CommandResult {
        require(LinuxTelemetryParser.isValidSystemdUnitName(unitName)) {
            "Invalid systemd unit name"
        }
        val result = if (sudoPassword != null) {
            val cmd = "sudo -S -p '' systemctl ${action.verb} '$unitName' 2>&1"
            executeCommand(cmd, timeoutMs = 15_000, stdinPayload = sudoPassword)
        } else {
            val cmd = """
                if [ "$(id -u)" -eq 0 ]; then
                  systemctl ${action.verb} '$unitName' 2>&1
                else
                  sudo -n systemctl ${action.verb} '$unitName' 2>&1
                fi
            """.trimIndent()
            executeCommand(cmd, timeoutMs = 15_000)
        }

        val combined = result.stdout + "\n" + result.stderr
        if (result.exitCode != 0 && sudoPassword != null && LinuxTelemetryParser.isIncorrectSudoPassword(combined)) {
            throw IncorrectSudoPasswordException("Incorrect sudo password for ${action.verb} $unitName")
        }
        if (result.exitCode != 0 && sudoPassword == null && LinuxTelemetryParser.isSudoPasswordRequired(combined)) {
            throw SudoPasswordRequiredException("Sudo password required to ${action.verb} $unitName")
        }
        return result
    }

    suspend fun fetchServiceStatus(unitName: String): CommandResult {
        require(LinuxTelemetryParser.isValidSystemdUnitName(unitName)) {
            "Invalid systemd unit name"
        }
        val cmd = "systemctl status '$unitName' -n 25 --no-pager 2>&1"
        return executeCommand(cmd, timeoutMs = 10_000)
    }

    suspend fun sendProcessSignal(
        pid: Int,
        force: Boolean = false,
        sudoPassword: String? = null
    ): CommandResult {
        require(pid > 1) { "Refusing to signal kernel/init PID $pid" }
        val signal = if (force) "KILL" else "TERM"
        val result = if (sudoPassword != null) {
            val cmd = "sudo -S -p '' kill -$signal $pid 2>&1"
            executeCommand(cmd, timeoutMs = 8_000, stdinPayload = sudoPassword)
        } else {
            val cmd = """
                if [ "$(id -u)" -eq 0 ]; then
                  kill -$signal $pid 2>&1
                else
                  sudo -n kill -$signal $pid 2>&1
                fi
            """.trimIndent()
            executeCommand(cmd, timeoutMs = 8_000)
        }

        val combined = result.stdout + "\n" + result.stderr
        if (result.exitCode != 0 && sudoPassword != null && LinuxTelemetryParser.isIncorrectSudoPassword(combined)) {
            throw IncorrectSudoPasswordException("Incorrect sudo password for signaling PID $pid")
        }
        if (result.exitCode != 0 && sudoPassword == null && LinuxTelemetryParser.isSudoPasswordRequired(combined)) {
            throw SudoPasswordRequiredException("Sudo password required to signal PID $pid")
        }
        return result
    }

    fun streamJournalLogs(
        unitFilter: String?,
        severity: LogSeverity
    ): Flow<LogEntry> = flow {
        val activeSession = synchronized(sessionLock) { session }
            ?: throw IllegalStateException("Not connected to VPS")
        if (!activeSession.isConnected) throw IllegalStateException("SSH session is closed")

        val unitFlag = if (!unitFilter.isNullOrBlank() && LinuxTelemetryParser.isValidSystemdUnitName(unitFilter)) {
            "-u '$unitFilter'"
        } else {
            ""
        }
        val priorityFlag = if (severity != LogSeverity.ALL) "-p 0..${severity.journalPriority}" else ""
        val cmd = """
            if command -v journalctl >/dev/null 2>&1; then
              journalctl -f -n 80 -o short-iso --no-pager $unitFlag $priorityFlag 2>&1
            elif [ -r /var/log/syslog ]; then
              tail -n 80 -f /var/log/syslog 2>&1
            elif [ -r /var/log/messages ]; then
              tail -n 80 -f /var/log/messages 2>&1
            else
              echo "No readable system journal or syslog found."
            fi
        """.trimIndent()

        val channel = activeSession.openChannel("exec") as ChannelExec
        channel.setCommand(cmd)
        val inputStream: InputStream = channel.inputStream

        try {
            channel.connect(10_000)
            val byteBuf = ByteArray(4096)
            val lineAccumulator = StringBuilder()

            suspend fun drainAvailable(): Boolean {
                var readAny = false
                while (inputStream.available() > 0) {
                    val count = inputStream.read(byteBuf, 0, minOf(inputStream.available(), byteBuf.size))
                    if (count <= 0) break
                    readAny = true
                    lineAccumulator.append(String(byteBuf, 0, count, Charsets.UTF_8))
                    while (true) {
                        val nlIdx = lineAccumulator.indexOf('\n')
                        if (nlIdx < 0) break
                        val line = lineAccumulator.substring(0, nlIdx)
                        lineAccumulator.delete(0, nlIdx + 1)
                        LinuxTelemetryParser.parseJournalLine(line)?.let { emit(it) }
                    }
                }
                return readAny
            }

            while (currentCoroutineContext().isActive) {
                val readAny = drainAvailable()
                if (channel.isClosed) {
                    drainAvailable()
                    if (lineAccumulator.isNotEmpty()) {
                        LinuxTelemetryParser.parseJournalLine(lineAccumulator.toString())?.let { emit(it) }
                        lineAccumulator.clear()
                    }
                    break
                }
                if (!readAny) {
                    delay(80L)
                }
            }
        } finally {
            runCatching { channel.disconnect() }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun openShell(cols: Int = 80, rows: Int = 24): SshShellHandle =
        withContext(Dispatchers.IO) {
            val activeSession = synchronized(sessionLock) { session }
                ?: throw IllegalStateException("Not connected to VPS")
            if (!activeSession.isConnected) throw IllegalStateException("SSH session is closed")

            val channel = activeSession.openChannel("shell") as ChannelShell
            channel.setPty(true)
            channel.setPtyType("xterm-256color", cols, rows, 0, 0)
            val out: OutputStream = channel.outputStream
            val input: InputStream = channel.inputStream
            channel.connect(10_000)

            object : SshShellHandle {
                override val outputFlow: Flow<String> = flow {
                    val buf = ByteArray(4096)
                    try {
                        while (currentCoroutineContext().isActive && channel.isConnected) {
                            val avail = input.available()
                            if (avail > 0) {
                                val count = input.read(buf, 0, minOf(avail, buf.size))
                                if (count > 0) {
                                    emit(String(buf, 0, count, Charsets.UTF_8))
                                } else if (count < 0) {
                                    break
                                }
                            } else {
                                if (channel.isClosed) break
                                delay(35L)
                            }
                        }
                    } finally {
                        runCatching { channel.disconnect() }
                    }
                }.flowOn(Dispatchers.IO)

                override suspend fun sendText(text: String) {
                    sendBytes(text.toByteArray(Charsets.UTF_8))
                }

                override suspend fun sendBytes(bytes: ByteArray) = withContext(Dispatchers.IO) {
                    if (channel.isConnected) {
                        out.write(bytes)
                        out.flush()
                    }
                }

                override suspend fun resizePty(cols: Int, rows: Int) = withContext(Dispatchers.IO) {
                    if (channel.isConnected) {
                        runCatching { channel.setPtySize(cols.coerceAtLeast(20), rows.coerceAtLeast(8), 0, 0) }
                    }
                }

                override fun close() {
                    runCatching { channel.disconnect() }
                }
            }
        }

    fun disconnect() {
        synchronized(sessionLock) {
            runCatching { session?.disconnect() }
            session = null
        }
    }

    private fun computeSha256Fingerprint(keyBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(keyBytes)
        val b64 = Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
        return "SHA256:$b64"
    }
}
