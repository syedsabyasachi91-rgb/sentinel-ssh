package com.example.domain.model

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val step: String) : ConnectionState
    data class AwaitingHostKeyApproval(
        val host: String,
        val port: Int,
        val fingerprintSha256: String,
        val isChanged: Boolean
    ) : ConnectionState
    data object Connected : ConnectionState
    data class Error(
        val message: String,
        val technicalDetails: String? = null
    ) : ConnectionState
}

enum class WorkspaceTab {
    MONITOR,
    SERVICES,
    LOGS,
    TERMINAL
}

enum class PollingInterval(val label: String, val millis: Long) {
    FAST("2s", 2_000L),
    NORMAL("5s", 5_000L),
    SLOW("10s", 10_000L)
}

data class DiskMount(
    val filesystem: String,
    val mountPoint: String,
    val usedBytes: Long,
    val totalBytes: Long,
    val usagePercent: Float
)

data class ProcessInfo(
    val pid: Int,
    val cpuPercent: Float,
    val memPercent: Float,
    val command: String
)

data class TelemetrySnapshot(
    val hostname: String = "—",
    val osDistro: String = "Linux",
    val kernelVersion: String = "—",
    val uptimeLabel: String = "—",
    val cpuTotalPercent: Float = 0f,
    val perCoreUsage: List<Float> = emptyList(),
    val load1m: Float = 0f,
    val load5m: Float = 0f,
    val load15m: Float = 0f,
    val memUsedMb: Long = 0L,
    val memTotalMb: Long = 0L,
    val memCachedMb: Long = 0L,
    val swapUsedMb: Long = 0L,
    val swapTotalMb: Long = 0L,
    val disks: List<DiskMount> = emptyList(),
    val netInterface: String = "eth0",
    val netRxBytesPerSec: Long = 0L,
    val netTxBytesPerSec: Long = 0L,
    val topProcesses: List<ProcessInfo> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis()
)

enum class ServiceAction(val verb: String, val displayLabel: String, val requiresConfirmation: Boolean) {
    START("start", "Start", false),
    RESTART("restart", "Restart", true),
    RELOAD("reload", "Reload", false),
    STOP("stop", "Stop", true)
}

enum class ServiceFilter(val label: String) {
    ALL("All"),
    ACTIVE("Active"),
    FAILED("Failed"),
    INACTIVE("Inactive")
}

data class SystemdUnit(
    val name: String,
    val loadState: String,
    val activeState: String,
    val subState: String,
    val description: String
) {
    val isActive: Boolean get() = activeState.equals("active", ignoreCase = true)
    val isFailed: Boolean get() = activeState.equals("failed", ignoreCase = true)
}

enum class LogSeverity(val label: String, val journalPriority: Int) {
    ALL("ALL", 7),
    ERR("ERR", 3),
    WARN("WARN", 4),
    INFO("INFO", 6)
}

data class LogEntry(
    val id: Long,
    val timestamp: String,
    val unit: String,
    val severity: LogSeverity,
    val message: String,
    val rawLine: String
)
