package com.example.ui.viewmodel

import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.CommandSnippetEntity
import com.example.data.local.FleetRepository
import com.example.data.local.ServerProfileEntity
import com.example.domain.model.ConnectionState
import com.example.domain.model.LogEntry
import com.example.domain.model.LogSeverity
import com.example.domain.model.PollingInterval
import com.example.domain.model.ServiceAction
import com.example.domain.model.ServiceFilter
import com.example.domain.model.SystemdUnit
import com.example.domain.model.TelemetrySnapshot
import com.example.domain.model.WorkspaceTab
import com.example.ssh.AnsiTerminalBuffer
import com.example.ssh.CpuTickSample
import com.example.ssh.HostKeyRejectedException
import com.example.ssh.IncorrectSudoPasswordException
import com.example.ssh.LinuxTelemetryParser
import com.example.ssh.NetByteSample
import com.example.ssh.SshAuthenticationException
import com.example.ssh.SshConnectionException
import com.example.ssh.SshSessionManager
import com.example.ssh.SshShellHandle
import com.example.ssh.SudoPasswordRequiredException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class MonitorUiState(
    val snapshot: TelemetrySnapshot = TelemetrySnapshot(),
    val cpuHistory: List<Float> = emptyList(),
    val netRxHistory: List<Long> = emptyList(),
    val netTxHistory: List<Long> = emptyList(),
    val pollingInterval: PollingInterval = PollingInterval.NORMAL,
    val isPollingPaused: Boolean = false,
    val isRefreshing: Boolean = false,
    val lastPollError: String? = null
)

data class ServicesUiState(
    val units: List<SystemdUnit> = emptyList(),
    val searchQuery: String = "",
    val filter: ServiceFilter = ServiceFilter.ALL,
    val isLoading: Boolean = false,
    val activeActionUnit: String? = null,
    val errorMessage: String? = null,
    val statusInspectUnit: String? = null,
    val statusInspectOutput: String = "",
    val isLoadingStatus: Boolean = false
)

data class LogsUiState(
    val entries: List<LogEntry> = emptyList(),
    val selectedUnit: String? = null,
    val severity: LogSeverity = LogSeverity.ALL,
    val searchQuery: String = "",
    val isFollowing: Boolean = true,
    val isStreaming: Boolean = false,
    val errorMessage: String? = null
)

data class TerminalUiState(
    val outputLines: List<AnnotatedString> = emptyList(),
    val commandInput: String = "",
    val commandHistory: List<String> = emptyList(),
    val historyCursor: Int = -1,
    val isShellConnected: Boolean = false,
    val isConnectingShell: Boolean = false,
    val errorMessage: String? = null
)

sealed interface PendingSudoAction {
    val description: String

    data class ServiceControl(
        val unitName: String,
        val action: ServiceAction
    ) : PendingSudoAction {
        override val description: String = "${action.displayLabel} $unitName"
    }

    data class ProcessSignal(
        val pid: Int,
        val force: Boolean
    ) : PendingSudoAction {
        override val description: String = "Send ${if (force) "SIGKILL" else "SIGTERM"} to PID $pid"
    }
}

class WorkspaceViewModel(
    private val serverId: Long,
    private val repository: FleetRepository,
    private val sshManager: SshSessionManager
) : ViewModel() {

    val serverProfile: StateFlow<ServerProfileEntity?> = repository.observeProfile(serverId)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = null
        )

    val snippets: StateFlow<List<CommandSnippetEntity>> = repository.allSnippets
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val _selectedTab = MutableStateFlow(WorkspaceTab.MONITOR)
    val selectedTab: StateFlow<WorkspaceTab> = _selectedTab.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Connecting("Decrypting credentials..."))
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _monitorState = MutableStateFlow(MonitorUiState())
    val monitorState: StateFlow<MonitorUiState> = _monitorState.asStateFlow()

    private val _servicesState = MutableStateFlow(ServicesUiState())
    val servicesState: StateFlow<ServicesUiState> = _servicesState.asStateFlow()

    private val _logsState = MutableStateFlow(LogsUiState())
    val logsState: StateFlow<LogsUiState> = _logsState.asStateFlow()

    private val _terminalState = MutableStateFlow(TerminalUiState())
    val terminalState: StateFlow<TerminalUiState> = _terminalState.asStateFlow()

    private val _pendingSudoAction = MutableStateFlow<PendingSudoAction?>(null)
    val pendingSudoAction: StateFlow<PendingSudoAction?> = _pendingSudoAction.asStateFlow()

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private var hostKeyDecisionDeferred: CompletableDeferred<Boolean>? = null
    private var shellReadyDeferred: CompletableDeferred<SshShellHandle?>? = null
    private var connectJob: Job? = null
    private var telemetryJob: Job? = null
    private var logsJob: Job? = null
    private var shellJob: Job? = null
    private var shellHandle: SshShellHandle? = null

    private var isAppInForeground: Boolean = true
    private var hasRecordedSessionConnection: Boolean = false
    private var ptyCols: Int = 84
    private var ptyRows: Int = 28

    private var previousCpuSamples: List<CpuTickSample> = emptyList()
    private var previousNetSample: NetByteSample? = null
    private val terminalBuffer = AnsiTerminalBuffer()

    init {
        connect()
    }

    fun setAppForeground(inForeground: Boolean) {
        if (isAppInForeground == inForeground) return
        isAppInForeground = inForeground
        if (!inForeground) {
            telemetryJob?.cancel()
        } else if (_connectionState.value is ConnectionState.Connected && !_monitorState.value.isPollingPaused) {
            startTelemetryLoop(serverId)
        }
    }

    fun selectTab(tab: WorkspaceTab) {
        _selectedTab.value = tab
        if (_connectionState.value is ConnectionState.Connected) {
            when (tab) {
                WorkspaceTab.MONITOR -> Unit
                WorkspaceTab.SERVICES -> if (_servicesState.value.units.isEmpty()) refreshServices()
                WorkspaceTab.LOGS -> if (!_logsState.value.isStreaming && _logsState.value.isFollowing) startLogStream()
                WorkspaceTab.TERMINAL -> if (!_terminalState.value.isShellConnected && !_terminalState.value.isConnectingShell) {
                    connectTerminalShell()
                }
            }
        }
    }

    fun connect() {
        connectJob?.cancel()
        stopActiveWorkloads()
        previousCpuSamples = emptyList()
        previousNetSample = null
        hasRecordedSessionConnection = false
        connectJob = viewModelScope.launch {
            _connectionState.value = ConnectionState.Connecting("Loading server profile...")
            val profile = repository.getProfile(serverId)
            if (profile == null) {
                _connectionState.value = ConnectionState.Error("Server profile was not found.")
                return@launch
            }

            _connectionState.value = ConnectionState.Connecting("Unlocking Android Keystore...")
            val credentials = repository.decryptCredentials(profile)
            if (credentials.decryptionError != null) {
                _connectionState.value = ConnectionState.Error(credentials.decryptionError)
                return@launch
            }
            if (credentials.secret.isEmpty()) {
                _connectionState.value = ConnectionState.Error("Missing SSH credentials. Please edit the server profile.")
                return@launch
            }

            _connectionState.value = ConnectionState.Connecting("Handshaking with ${profile.host}:${profile.port}...")
            try {
                sshManager.connect(
                    profile = profile,
                    credentials = credentials,
                    onVerifyHostKey = { fingerprint, isChanged ->
                        val deferred = CompletableDeferred<Boolean>()
                        hostKeyDecisionDeferred = deferred
                        _connectionState.value = ConnectionState.AwaitingHostKeyApproval(
                            host = profile.host,
                            port = profile.port,
                            fingerprintSha256 = fingerprint,
                            isChanged = isChanged
                        )
                        val approved = deferred.await()
                        if (approved) {
                            _connectionState.value = ConnectionState.Connecting("Authenticating ${profile.username}...")
                        }
                        approved
                    },
                    onFingerprintTrusted = { fingerprint ->
                        repository.saveTrustedFingerprint(profile.id, fingerprint)
                    }
                )

                _connectionState.value = ConnectionState.Connected
                startTelemetryLoop(profile.id)
                refreshServices()
                if (_selectedTab.value == WorkspaceTab.LOGS && _logsState.value.isFollowing) {
                    startLogStream()
                } else if (_selectedTab.value == WorkspaceTab.TERMINAL) {
                    connectTerminalShell()
                }
            } catch (e: HostKeyRejectedException) {
                if (_connectionState.value !is ConnectionState.Disconnected) {
                    _connectionState.value = ConnectionState.Error(
                        message = e.message ?: "Host key rejected.",
                        technicalDetails = e.technicalDetails
                    )
                }
            } catch (e: SshAuthenticationException) {
                _connectionState.value = ConnectionState.Error(
                    message = e.message ?: "SSH authentication failed.",
                    technicalDetails = e.technicalDetails
                )
            } catch (e: SshConnectionException) {
                _connectionState.value = ConnectionState.Error(
                    message = e.message ?: "Unable to connect to ${profile.host}:${profile.port}",
                    technicalDetails = e.technicalDetails
                )
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Error(
                    message = e.message?.takeIf { it.isNotBlank() }
                        ?: "Unable to connect to ${profile.host}:${profile.port}"
                )
            }
        }
    }

    fun respondToHostKey(trust: Boolean) {
        hostKeyDecisionDeferred?.complete(trust)
        hostKeyDecisionDeferred = null
        if (!trust) {
            _connectionState.value = ConnectionState.Disconnected
        }
    }

    fun disconnect() {
        hostKeyDecisionDeferred?.cancel()
        hostKeyDecisionDeferred = null
        connectJob?.cancel()
        stopActiveWorkloads()
        sshManager.disconnect()
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun stopActiveWorkloads() {
        telemetryJob?.cancel()
        telemetryJob = null
        logsJob?.cancel()
        logsJob = null
        shellJob?.cancel()
        shellJob = null
        shellReadyDeferred?.complete(null)
        shellReadyDeferred = null
        shellHandle?.close()
        shellHandle = null
        _terminalState.update { it.copy(isShellConnected = false, isConnectingShell = false) }
        _logsState.update { it.copy(isStreaming = false) }
    }

    // --- Monitor Telemetry ---

    fun setPollingInterval(interval: PollingInterval) {
        _monitorState.update { it.copy(pollingInterval = interval) }
        if (_connectionState.value is ConnectionState.Connected && !_monitorState.value.isPollingPaused && isAppInForeground) {
            startTelemetryLoop(serverId)
        }
    }

    fun togglePollingPaused() {
        val nextPaused = !_monitorState.value.isPollingPaused
        _monitorState.update { it.copy(isPollingPaused = nextPaused) }
        if (nextPaused) {
            telemetryJob?.cancel()
        } else if (_connectionState.value is ConnectionState.Connected && isAppInForeground) {
            startTelemetryLoop(serverId)
        }
    }

    fun refreshTelemetryNow() {
        if (_connectionState.value !is ConnectionState.Connected) return
        viewModelScope.launch {
            pollTelemetryOnce()
        }
    }

    private fun startTelemetryLoop(profileId: Long) {
        telemetryJob?.cancel()
        telemetryJob = viewModelScope.launch {
            // Immediate fast delta warmup if this is the initial sample
            if (previousCpuSamples.isEmpty() && isActive) {
                pollTelemetryOnce()
                delay(350L)
            }
            while (isActive && _connectionState.value is ConnectionState.Connected && !_monitorState.value.isPollingPaused && isAppInForeground) {
                val snapshot = pollTelemetryOnce()
                if (snapshot != null && !hasRecordedSessionConnection) {
                    hasRecordedSessionConnection = true
                    repository.recordSuccessfulConnection(
                        serverId = profileId,
                        osSummary = "${snapshot.osDistro} • ${snapshot.hostname}"
                    )
                }
                delay(_monitorState.value.pollingInterval.millis)
            }
        }
    }

    private suspend fun pollTelemetryOnce(): TelemetrySnapshot? {
        _monitorState.update { it.copy(isRefreshing = true, lastPollError = null) }
        return runCatching {
            val result = sshManager.executeCommand(LinuxTelemetryParser.telemetryProbeCommand, timeoutMs = 10_000)
            val parsed = LinuxTelemetryParser.parseTelemetry(
                rawOutput = result.stdout,
                previousCpuSamples = previousCpuSamples,
                previousNetSample = previousNetSample
            )
            previousCpuSamples = parsed.cpuSamples
            previousNetSample = parsed.netSample

            _monitorState.update { state ->
                val newCpuHistory = (state.cpuHistory + parsed.snapshot.cpuTotalPercent).takeLast(HISTORY_POINTS)
                val newRxHistory = (state.netRxHistory + parsed.snapshot.netRxBytesPerSec).takeLast(HISTORY_POINTS)
                val newTxHistory = (state.netTxHistory + parsed.snapshot.netTxBytesPerSec).takeLast(HISTORY_POINTS)
                state.copy(
                    snapshot = parsed.snapshot,
                    cpuHistory = newCpuHistory,
                    netRxHistory = newRxHistory,
                    netTxHistory = newTxHistory,
                    isRefreshing = false,
                    lastPollError = null
                )
            }
            parsed.snapshot
        }.getOrElse { err ->
            _monitorState.update {
                it.copy(isRefreshing = false, lastPollError = err.message ?: "Telemetry probe failed")
            }
            if (!sshManager.isConnected) {
                _connectionState.value = ConnectionState.Error("SSH connection lost. Tap Reconnect.")
                stopActiveWorkloads()
            }
            null
        }
    }

    fun terminateProcess(pid: Int, force: Boolean, sudoPassword: String? = null) {
        viewModelScope.launch {
            runCatching {
                val res = sshManager.sendProcessSignal(pid, force, sudoPassword)
                if (res.exitCode != 0) {
                    throw IllegalStateException(res.stdout.ifBlank { res.stderr }.ifBlank { "Exit code ${res.exitCode}" })
                }
            }.onSuccess {
                _snackbarMessage.value = "Sent ${if (force) "SIGKILL" else "SIGTERM"} to PID $pid"
                refreshTelemetryNow()
            }.onFailure { err ->
                when {
                    err is SudoPasswordRequiredException && sudoPassword == null -> {
                        _pendingSudoAction.value = PendingSudoAction.ProcessSignal(pid, force)
                    }
                    err is IncorrectSudoPasswordException -> {
                        _snackbarMessage.value = "Incorrect sudo password. Signal was not sent."
                    }
                    else -> {
                        _snackbarMessage.value = "Signal failed: ${err.message}"
                    }
                }
            }
        }
    }

    // --- Systemd Services ---

    fun updateServiceSearch(query: String) {
        _servicesState.update { it.copy(searchQuery = query) }
    }

    fun updateServiceFilter(filter: ServiceFilter) {
        _servicesState.update { it.copy(filter = filter) }
    }

    fun refreshServices() {
        if (_connectionState.value !is ConnectionState.Connected) return
        viewModelScope.launch {
            _servicesState.update { it.copy(isLoading = true, errorMessage = null) }
            val cmd = "systemctl list-units --type=service --all --no-pager --no-legend --plain 2>&1"
            runCatching {
                val res = sshManager.executeCommand(cmd, timeoutMs = 12_000)
                if (res.exitCode != 0 && res.stdout.contains("not found", ignoreCase = true)) {
                    throw IllegalStateException("systemd is not available on this host.")
                }
                LinuxTelemetryParser.parseSystemdUnits(res.stdout)
            }.onSuccess { units ->
                _servicesState.update { it.copy(units = units, isLoading = false, errorMessage = null) }
            }.onFailure { err ->
                _servicesState.update {
                    it.copy(isLoading = false, errorMessage = err.message ?: "Failed to load services")
                }
            }
        }
    }

    fun controlService(unitName: String, action: ServiceAction, sudoPassword: String? = null) {
        viewModelScope.launch {
            _servicesState.update { it.copy(activeActionUnit = unitName) }
            runCatching {
                val res = sshManager.controlSystemdService(unitName, action, sudoPassword)
                if (res.exitCode != 0) {
                    val detail = res.stdout.ifBlank { res.stderr }.trim()
                    throw IllegalStateException(detail.ifEmpty { "Exit code ${res.exitCode}" })
                }
            }.onSuccess {
                _snackbarMessage.value = "${action.displayLabel} succeeded for $unitName"
                refreshServices()
            }.onFailure { err ->
                when {
                    err is SudoPasswordRequiredException && sudoPassword == null -> {
                        _pendingSudoAction.value = PendingSudoAction.ServiceControl(unitName, action)
                    }
                    err is IncorrectSudoPasswordException -> {
                        _snackbarMessage.value = "Incorrect sudo password. Action was not executed."
                    }
                    else -> {
                        _snackbarMessage.value = "Action failed: ${err.message}"
                    }
                }
            }
            _servicesState.update { it.copy(activeActionUnit = null) }
        }
    }

    fun inspectServiceStatus(unitName: String) {
        if (_connectionState.value !is ConnectionState.Connected) return
        viewModelScope.launch {
            _servicesState.update {
                it.copy(statusInspectUnit = unitName, statusInspectOutput = "", isLoadingStatus = true)
            }
            runCatching {
                sshManager.fetchServiceStatus(unitName)
            }.onSuccess { res ->
                val text = (res.stdout + "\n" + res.stderr).trim().ifEmpty { "No status output returned." }
                _servicesState.update {
                    it.copy(statusInspectOutput = text, isLoadingStatus = false)
                }
            }.onFailure { err ->
                _servicesState.update {
                    it.copy(
                        statusInspectOutput = err.message ?: "Unable to fetch service status.",
                        isLoadingStatus = false
                    )
                }
            }
        }
    }

    fun dismissServiceStatus() {
        _servicesState.update {
            it.copy(statusInspectUnit = null, statusInspectOutput = "", isLoadingStatus = false)
        }
    }

    fun submitSudoPassword(password: String) {
        val pending = _pendingSudoAction.value ?: return
        _pendingSudoAction.value = null
        when (pending) {
            is PendingSudoAction.ServiceControl ->
                controlService(pending.unitName, pending.action, sudoPassword = password)
            is PendingSudoAction.ProcessSignal ->
                terminateProcess(pending.pid, pending.force, sudoPassword = password)
        }
    }

    fun dismissSudoPrompt() {
        _pendingSudoAction.value = null
    }

    fun inspectUnitInLogs(unitName: String) {
        _logsState.update { it.copy(selectedUnit = unitName, entries = emptyList(), isFollowing = true) }
        _selectedTab.value = WorkspaceTab.LOGS
        startLogStream()
    }

    // --- Logs Viewer ---

    fun updateLogUnitFilter(unitName: String?) {
        _logsState.update { it.copy(selectedUnit = unitName, entries = emptyList()) }
        if (_logsState.value.isFollowing) startLogStream()
    }

    fun updateLogSeverity(severity: LogSeverity) {
        _logsState.update { it.copy(severity = severity, entries = emptyList()) }
        if (_logsState.value.isFollowing) startLogStream()
    }

    fun updateLogSearch(query: String) {
        _logsState.update { it.copy(searchQuery = query) }
    }

    fun toggleLogFollow() {
        val nextFollow = !_logsState.value.isFollowing
        _logsState.update { it.copy(isFollowing = nextFollow) }
        if (nextFollow) {
            startLogStream()
        } else {
            logsJob?.cancel()
            _logsState.update { it.copy(isStreaming = false) }
        }
    }

    fun clearLogBuffer() {
        _logsState.update { it.copy(entries = emptyList()) }
    }

    fun startLogStream() {
        if (_connectionState.value !is ConnectionState.Connected) return
        logsJob?.cancel()
        val unit = _logsState.value.selectedUnit
        val severity = _logsState.value.severity
        logsJob = viewModelScope.launch {
            _logsState.update { it.copy(isStreaming = true, errorMessage = null) }
            sshManager.streamJournalLogs(unit, severity)
                .catch { err ->
                    _logsState.update {
                        it.copy(isStreaming = false, errorMessage = err.message ?: "Log stream stopped")
                    }
                }
                .collect { entry ->
                    _logsState.update { state ->
                        val updated = (state.entries + entry).takeLast(MAX_LOG_ENTRIES)
                        state.copy(entries = updated)
                    }
                }
            _logsState.update { it.copy(isStreaming = false) }
        }
    }

    // --- Interactive Terminal & Snippets ---

    fun connectTerminalShell() {
        if (_connectionState.value !is ConnectionState.Connected) return
        shellJob?.cancel()
        shellReadyDeferred?.complete(null)
        shellHandle?.close()
        val ready = CompletableDeferred<SshShellHandle?>()
        shellReadyDeferred = ready
        shellJob = viewModelScope.launch {
            _terminalState.update { it.copy(isConnectingShell = true, errorMessage = null) }
            runCatching {
                sshManager.openShell(cols = ptyCols, rows = ptyRows)
            }.onSuccess { handle ->
                shellHandle = handle
                _terminalState.update {
                    it.copy(isConnectingShell = false, isShellConnected = true, errorMessage = null)
                }
                ready.complete(handle)
                handle.outputFlow
                    .catch { err ->
                        _terminalState.update {
                            it.copy(isShellConnected = false, errorMessage = err.message ?: "Shell closed")
                        }
                    }
                    .collect { chunk ->
                        val lines = terminalBuffer.appendChunkLines(chunk)
                        _terminalState.update { it.copy(outputLines = lines) }
                    }
                _terminalState.update { it.copy(isShellConnected = false) }
            }.onFailure { err ->
                ready.complete(null)
                _terminalState.update {
                    it.copy(
                        isConnectingShell = false,
                        isShellConnected = false,
                        errorMessage = err.message ?: "Failed to open interactive PTY shell"
                    )
                }
            }
        }
    }

    private suspend fun ensureShellHandle(): SshShellHandle? {
        val existing = shellHandle
        if (existing != null && _terminalState.value.isShellConnected) {
            return existing
        }
        if (_connectionState.value !is ConnectionState.Connected) {
            return null
        }
        if (!_terminalState.value.isConnectingShell) {
            connectTerminalShell()
        }
        return withTimeoutOrNull(12_000L) {
            shellReadyDeferred?.await()
        }
    }

    fun resizeTerminalPty(cols: Int, rows: Int) {
        val clampedCols = cols.coerceIn(24, 240)
        val clampedRows = rows.coerceIn(10, 100)
        if (clampedCols == ptyCols && clampedRows == ptyRows) return
        ptyCols = clampedCols
        ptyRows = clampedRows
        viewModelScope.launch {
            shellHandle?.resizePty(clampedCols, clampedRows)
        }
    }

    fun updateTerminalInput(value: String) {
        _terminalState.update { it.copy(commandInput = value, historyCursor = -1) }
    }

    fun submitTerminalCommand() {
        val cmd = _terminalState.value.commandInput
        _terminalState.update { state ->
            val updatedHistory = if (cmd.isNotBlank() && state.commandHistory.lastOrNull() != cmd) {
                (state.commandHistory + cmd).takeLast(50)
            } else {
                state.commandHistory
            }
            state.copy(commandInput = "", commandHistory = updatedHistory, historyCursor = -1)
        }
        sendRawToShell("$cmd\n")
    }

    fun handleArrowKey(up: Boolean) {
        val state = _terminalState.value
        val history = state.commandHistory
        if (history.isNotEmpty() && (up || state.historyCursor >= 0 || state.commandInput.isNotEmpty())) {
            val nextCursor = if (up) {
                if (state.historyCursor < 0) history.lastIndex else (state.historyCursor - 1).coerceAtLeast(0)
            } else {
                if (state.historyCursor < 0) -1
                else if (state.historyCursor + 1 >= history.size) -1
                else state.historyCursor + 1
            }
            _terminalState.update {
                it.copy(
                    historyCursor = nextCursor,
                    commandInput = if (nextCursor in history.indices) history[nextCursor] else ""
                )
            }
        } else {
            sendRawToShell(if (up) "\u001B[A" else "\u001B[B")
        }
    }

    fun sendRawToShell(text: String) {
        viewModelScope.launch {
            val handle = ensureShellHandle()
            if (handle == null) {
                _snackbarMessage.value = "Interactive shell is not connected."
                return@launch
            }
            runCatching { handle.sendText(text) }.onFailure { err ->
                _snackbarMessage.value = "Shell write failed: ${err.message ?: "Disconnected"}"
            }
        }
    }

    fun sendControlBytesToShell(bytes: ByteArray) {
        viewModelScope.launch {
            val handle = ensureShellHandle()
            if (handle == null) {
                _snackbarMessage.value = "Interactive shell is not connected."
                return@launch
            }
            runCatching { handle.sendBytes(bytes) }.onFailure { err ->
                _snackbarMessage.value = "Shell write failed: ${err.message ?: "Disconnected"}"
            }
        }
    }

    fun clearTerminalScreen() {
        terminalBuffer.clear()
        _terminalState.update { it.copy(outputLines = emptyList()) }
    }

    fun insertSnippet(command: String) {
        _terminalState.update { it.copy(commandInput = command, historyCursor = -1) }
    }

    fun runSnippet(command: String) {
        _selectedTab.value = WorkspaceTab.TERMINAL
        _terminalState.update { state ->
            val updatedHistory = if (command.isNotBlank() && state.commandHistory.lastOrNull() != command) {
                (state.commandHistory + command).takeLast(50)
            } else {
                state.commandHistory
            }
            state.copy(commandHistory = updatedHistory, historyCursor = -1)
        }
        sendRawToShell("$command\n")
    }

    fun saveCustomSnippet(title: String, command: String, category: String) {
        if (title.isBlank() || command.isBlank()) return
        viewModelScope.launch {
            repository.addCustomSnippet(title, command, category)
            _snackbarMessage.value = "Snippet saved"
        }
    }

    fun deleteSnippet(snippetId: Long) {
        viewModelScope.launch {
            repository.deleteSnippet(snippetId)
        }
    }

    fun dismissSnackbar() {
        _snackbarMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        hostKeyDecisionDeferred?.cancel()
        stopActiveWorkloads()
        sshManager.disconnect()
    }

    companion object {
        private const val HISTORY_POINTS = 30
        private const val MAX_LOG_ENTRIES = 400
    }
}
