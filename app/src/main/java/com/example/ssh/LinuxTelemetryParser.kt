package com.example.ssh

import com.example.domain.model.DiskMount
import com.example.domain.model.LogEntry
import com.example.domain.model.LogSeverity
import com.example.domain.model.ProcessInfo
import com.example.domain.model.SystemdUnit
import com.example.domain.model.TelemetrySnapshot
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class CpuTickSample(
    val label: String,
    val totalTicks: Long,
    val idleTicks: Long
)

data class NetByteSample(
    val primaryInterface: String,
    val rxBytes: Long,
    val txBytes: Long,
    val timestampMs: Long
)

data class ParsedTelemetryOutput(
    val snapshot: TelemetrySnapshot,
    val cpuSamples: List<CpuTickSample>,
    val netSample: NetByteSample
)

object LinuxTelemetryParser {

    private val unitNameRegex = Regex("^[a-zA-Z0-9@_.:-]+$")
    private val logIdGen = AtomicLong(1L)

    val telemetryProbeCommand: String = """
        echo "===HOST==="; hostname 2>/dev/null || uname -n
        echo "===OS==="; grep -m1 '^PRETTY_NAME=' /etc/os-release 2>/dev/null | cut -d= -f2- | tr -d '"' || uname -s
        echo "===KERNEL==="; uname -r
        echo "===UPTIME==="; uptime -p 2>/dev/null || awk '{printf "up %d days, %d hrs\n", int($1/86400), int(($1%86400)/3600)}' /proc/uptime
        echo "===LOAD==="; cat /proc/loadavg 2>/dev/null
        echo "===STAT==="; grep '^cpu' /proc/stat 2>/dev/null
        echo "===MEM==="; head -n 25 /proc/meminfo 2>/dev/null
        echo "===DF==="; df -Pk 2>/dev/null | awk 'NR>1 && ($1 ~ /^\/dev\// || $6 == "/")'
        echo "===NET==="; cat /proc/net/dev 2>/dev/null
        echo "===PS==="; (ps -eo pid,pcpu,pmem,comm --sort=-pcpu 2>/dev/null || ps -eo pid,pcpu,pmem,comm 2>/dev/null) | head -n 11
    """.trimIndent()

    fun isValidSystemdUnitName(unitName: String): Boolean {
        return unitName.isNotBlank() && unitName.length <= 128 && unitNameRegex.matches(unitName)
    }

    fun normalizeHostInput(rawHost: String): String {
        var cleaned = rawHost.trim()
        listOf("ssh://", "sftp://", "https://", "http://").forEach { scheme ->
            if (cleaned.startsWith(scheme, ignoreCase = true)) {
                cleaned = cleaned.substring(scheme.length)
            }
        }
        cleaned = cleaned.substringBefore('/').trim()
        if ('@' in cleaned) {
            cleaned = cleaned.substringAfterLast('@').trim()
        }
        if (cleaned.startsWith("[") && cleaned.contains("]")) {
            cleaned = cleaned.substringAfter("[").substringBefore("]")
        }
        return cleaned
    }

    fun validatePrivateKeyFormat(keyText: String): String? {
        val trimmed = keyText.trim()
        return when {
            trimmed.isEmpty() -> "SSH private key is required."
            trimmed.startsWith("ssh-rsa ") ||
                trimmed.startsWith("ssh-ed25519 ") ||
                trimmed.startsWith("ecdsa-sha2-") ->
                "This looks like a public key (.pub). Please provide your private key."
            trimmed.startsWith("PuTTY-User-Key-File") ->
                "PuTTY (.ppk) format is not supported directly. Export as OpenSSH or PEM first."
            !trimmed.contains("-----BEGIN ") || !trimmed.contains("PRIVATE KEY-----") ->
                "Unrecognized private key format. Expected PEM or OpenSSH BEGIN/END PRIVATE KEY headers."
            else -> null
        }
    }

    fun isSudoPasswordRequired(output: String): Boolean {
        val lower = output.lowercase(Locale.ROOT)
        return "a password is required" in lower ||
            "no password was provided" in lower ||
            "password is required" in lower ||
            "terminal is required to read the password" in lower
    }

    fun isIncorrectSudoPassword(output: String): Boolean {
        val lower = output.lowercase(Locale.ROOT)
        return "sorry, try again" in lower ||
            "incorrect password attempt" in lower ||
            "authentication failure" in lower
    }

    fun parseTelemetry(
        rawOutput: String,
        previousCpuSamples: List<CpuTickSample>,
        previousNetSample: NetByteSample?,
        nowMs: Long = System.currentTimeMillis()
    ): ParsedTelemetryOutput {
        val sections = splitSections(rawOutput)

        val hostname = sections["HOST"]?.firstOrNull()?.trim().orEmpty().ifEmpty { "vps-node" }
        val osDistro = sections["OS"]?.firstOrNull()?.trim().orEmpty().ifEmpty { "Linux" }
        val kernel = sections["KERNEL"]?.firstOrNull()?.trim().orEmpty().ifEmpty { "—" }
        val uptime = sections["UPTIME"]?.firstOrNull()?.trim().orEmpty().ifEmpty { "—" }

        val loadParts = sections["LOAD"]?.firstOrNull()?.trim()?.split(Regex("\\s+")).orEmpty()
        val load1 = loadParts.getOrNull(0)?.toFloatOrNull() ?: 0f
        val load5 = loadParts.getOrNull(1)?.toFloatOrNull() ?: 0f
        val load15 = loadParts.getOrNull(2)?.toFloatOrNull() ?: 0f

        val cpuSamples = parseCpuSamples(sections["STAT"].orEmpty())
        val prevMap = previousCpuSamples.associateBy { it.label }
        val totalSample = cpuSamples.firstOrNull { it.label == "cpu" }
        val totalCpuPct = if (totalSample != null) {
            calculateCpuPercent(prevMap["cpu"], totalSample)
        } else {
            0f
        }
        val perCorePct = cpuSamples
            .filter { it.label.startsWith("cpu") && it.label != "cpu" }
            .map { sample -> calculateCpuPercent(prevMap[sample.label], sample) }

        val memMap = parseMemInfo(sections["MEM"].orEmpty())
        val memTotalKb = memMap["MemTotal"] ?: 0L
        val memAvailKb = memMap["MemAvailable"]
            ?: ((memMap["MemFree"] ?: 0L) + (memMap["Buffers"] ?: 0L) + (memMap["Cached"] ?: 0L))
        val memUsedKb = (memTotalKb - memAvailKb).coerceAtLeast(0L)
        val memCachedKb = (memMap["Cached"] ?: 0L) + (memMap["Buffers"] ?: 0L)
        val swapTotalKb = memMap["SwapTotal"] ?: 0L
        val swapFreeKb = memMap["SwapFree"] ?: 0L
        val swapUsedKb = (swapTotalKb - swapFreeKb).coerceAtLeast(0L)

        val disks = parseDisks(sections["DF"].orEmpty())
        val netSample = parseNetSample(sections["NET"].orEmpty(), nowMs)
        val (rxRate, txRate) = calculateNetRates(previousNetSample, netSample)
        val processes = parseProcesses(sections["PS"].orEmpty())

        val snapshot = TelemetrySnapshot(
            hostname = hostname,
            osDistro = osDistro,
            kernelVersion = kernel,
            uptimeLabel = uptime,
            cpuTotalPercent = totalCpuPct,
            perCoreUsage = perCorePct,
            load1m = load1,
            load5m = load5,
            load15m = load15,
            memUsedMb = memUsedKb / 1024L,
            memTotalMb = memTotalKb / 1024L,
            memCachedMb = memCachedKb / 1024L,
            swapUsedMb = swapUsedKb / 1024L,
            swapTotalMb = swapTotalKb / 1024L,
            disks = disks,
            netInterface = netSample.primaryInterface,
            netRxBytesPerSec = rxRate,
            netTxBytesPerSec = txRate,
            topProcesses = processes,
            timestampMs = nowMs
        )

        return ParsedTelemetryOutput(
            snapshot = snapshot,
            cpuSamples = cpuSamples,
            netSample = netSample
        )
    }

    private fun splitSections(raw: String): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        var currentKey: String? = null
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("===") && trimmed.endsWith("===") && trimmed.length > 6) {
                val key = trimmed.removePrefix("===").removeSuffix("===")
                currentKey = key
                result.putIfAbsent(key, mutableListOf())
            } else if (currentKey != null && line.isNotBlank()) {
                result[currentKey]?.add(line)
            }
        }
        return result
    }

    private fun parseCpuSamples(lines: List<String>): List<CpuTickSample> {
        return lines.mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 5 || !parts[0].startsWith("cpu")) return@mapNotNull null
            val numbers = parts.drop(1).mapNotNull { it.toLongOrNull() }
            if (numbers.size < 4) return@mapNotNull null
            val idle = numbers[3] + (numbers.getOrNull(4) ?: 0L)
            val total = numbers.sum()
            CpuTickSample(label = parts[0], totalTicks = total, idleTicks = idle)
        }
    }

    private fun calculateCpuPercent(prev: CpuTickSample?, current: CpuTickSample): Float {
        if (prev == null) {
            if (current.totalTicks <= 0L) return 0f
            val busy = (current.totalTicks - current.idleTicks).coerceAtLeast(0L)
            return ((busy.toDouble() / current.totalTicks.toDouble()) * 100.0).toFloat().coerceIn(0f, 100f)
        }
        val deltaTotal = current.totalTicks - prev.totalTicks
        val deltaIdle = current.idleTicks - prev.idleTicks
        if (deltaTotal <= 0L) return 0f
        val deltaBusy = (deltaTotal - deltaIdle).coerceAtLeast(0L)
        return ((deltaBusy.toDouble() / deltaTotal.toDouble()) * 100.0).toFloat().coerceIn(0f, 100f)
    }

    private fun parseMemInfo(lines: List<String>): Map<String, Long> {
        val map = mutableMapOf<String, Long>()
        lines.forEach { line ->
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim()
                val valueStr = line.substring(colonIdx + 1).trim().split(Regex("\\s+")).firstOrNull()
                val kb = valueStr?.toLongOrNull()
                if (kb != null) map[key] = kb
            }
        }
        return map
    }

    private fun parseDisks(lines: List<String>): List<DiskMount> {
        return lines.mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 6) return@mapNotNull null
            val fs = parts[0]
            val totalKb = parts[1].toLongOrNull() ?: return@mapNotNull null
            val usedKb = parts[2].toLongOrNull() ?: return@mapNotNull null
            val mount = parts.drop(5).joinToString(" ")
            if (totalKb <= 0L) return@mapNotNull null
            val pct = ((usedKb.toDouble() / totalKb.toDouble()) * 100.0).toFloat().coerceIn(0f, 100f)
            DiskMount(
                filesystem = fs,
                mountPoint = mount,
                usedBytes = usedKb * 1024L,
                totalBytes = totalKb * 1024L,
                usagePercent = pct
            )
        }.distinctBy { it.mountPoint }
    }

    private fun parseNetSample(lines: List<String>, nowMs: Long): NetByteSample {
        var totalRx = 0L
        var totalTx = 0L
        var primary = "eth0"
        var maxBytes = -1L

        lines.forEach { line ->
            val colonIdx = line.indexOf(':')
            if (colonIdx <= 0) return@forEach
            val iface = line.substring(0, colonIdx).trim()
            if (iface == "lo" || iface.startsWith("veth") || iface.startsWith("br-")) return@forEach
            val fields = line.substring(colonIdx + 1).trim().split(Regex("\\s+"))
            if (fields.size >= 10) {
                val rx = fields[0].toLongOrNull() ?: 0L
                val tx = fields[8].toLongOrNull() ?: 0L
                totalRx += rx
                totalTx += tx
                if (rx + tx > maxBytes) {
                    maxBytes = rx + tx
                    primary = iface
                }
            }
        }

        return NetByteSample(
            primaryInterface = primary,
            rxBytes = totalRx,
            txBytes = totalTx,
            timestampMs = nowMs
        )
    }

    private fun calculateNetRates(prev: NetByteSample?, current: NetByteSample): Pair<Long, Long> {
        if (prev == null) return 0L to 0L
        val elapsedMs = (current.timestampMs - prev.timestampMs).coerceAtLeast(250L)
        val rxDelta = (current.rxBytes - prev.rxBytes).coerceAtLeast(0L)
        val txDelta = (current.txBytes - prev.txBytes).coerceAtLeast(0L)
        val rxRate = (rxDelta * 1000L) / elapsedMs
        val txRate = (txDelta * 1000L) / elapsedMs
        return rxRate to txRate
    }

    private fun parseProcesses(lines: List<String>): List<ProcessInfo> {
        return lines.mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 4)
            if (parts.size < 4) return@mapNotNull null
            val pid = parts[0].toIntOrNull() ?: return@mapNotNull null
            val cpu = parts[1].toFloatOrNull() ?: 0f
            val mem = parts[2].toFloatOrNull() ?: 0f
            val comm = parts[3]
            ProcessInfo(pid = pid, cpuPercent = cpu, memPercent = mem, command = comm)
        }
    }

    fun parseSystemdUnits(rawOutput: String): List<SystemdUnit> {
        return rawOutput.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("UNIT ") }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"), limit = 5)
                if (parts.size < 4) return@mapNotNull null
                val name = parts[0].trimStart('●', '*', ' ')
                if (!name.endsWith(".service")) return@mapNotNull null
                SystemdUnit(
                    name = name,
                    loadState = parts[1],
                    activeState = parts[2],
                    subState = parts[3],
                    description = parts.getOrNull(4)?.trim().orEmpty()
                )
            }
            .sortedWith(
                compareBy<SystemdUnit> {
                    when {
                        it.isFailed -> 0
                        it.isActive -> 1
                        else -> 2
                    }
                }.thenBy { it.name }
            )
            .toList()
    }

    fun parseJournalLine(rawLine: String): LogEntry? {
        val trimmed = rawLine.trimEnd()
        if (trimmed.isEmpty() || trimmed.startsWith("-- ")) return null

        // Fallback syslog format: "Nov 22 07:55:01 hostname unit[pid]: message"
        val syslogMatch = syslogRegex.matchEntire(trimmed)
        if (syslogMatch != null) {
            val timestamp = syslogMatch.groupValues[1]
            val unit = syslogMatch.groupValues[2].substringBefore('[').trim().ifEmpty { "system" }
            val message = syslogMatch.groupValues[3]
            return LogEntry(
                id = logIdGen.getAndIncrement(),
                timestamp = timestamp,
                unit = unit,
                severity = inferSeverity(message),
                message = message,
                rawLine = trimmed
            )
        }

        // short-iso format: 2026-11-22T07:50:12+0000 hostname unit[pid]: message
        val firstSpace = trimmed.indexOf(' ')
        if (firstSpace <= 0) {
            return LogEntry(
                id = logIdGen.getAndIncrement(),
                timestamp = "—",
                unit = "system",
                severity = inferSeverity(trimmed),
                message = trimmed,
                rawLine = trimmed
            )
        }

        val rawTimestamp = trimmed.substring(0, firstSpace)
        val displayTime = if (rawTimestamp.contains('T') && rawTimestamp.length >= 19) {
            rawTimestamp.substring(11, 19)
        } else {
            rawTimestamp.take(15)
        }

        val rest = trimmed.substring(firstSpace + 1).trimStart()
        val afterHostIdx = rest.indexOf(' ')
        if (afterHostIdx <= 0) {
            return LogEntry(
                id = logIdGen.getAndIncrement(),
                timestamp = displayTime,
                unit = "system",
                severity = inferSeverity(rest),
                message = rest,
                rawLine = trimmed
            )
        }

        val afterHost = rest.substring(afterHostIdx + 1).trimStart()
        val colonIdx = afterHost.indexOf(": ")
        val (unit, message) = if (colonIdx > 0) {
            val rawUnit = afterHost.substring(0, colonIdx).substringBefore('[').trim()
            val msg = afterHost.substring(colonIdx + 2)
            rawUnit.ifEmpty { "kernel" } to msg
        } else {
            "system" to afterHost
        }

        return LogEntry(
            id = logIdGen.getAndIncrement(),
            timestamp = displayTime,
            unit = unit,
            severity = inferSeverity(message),
            message = message,
            rawLine = trimmed
        )
    }

    private val syslogRegex = Regex("^[A-Z][a-z]{2}\\s+\\d{1,2}\\s+(\\d{2}:\\d{2}:\\d{2})\\s+\\S+\\s+([^:]+):\\s*(.*)$")

    private fun inferSeverity(text: String): LogSeverity {
        val lower = text.lowercase(Locale.ROOT)
        return when {
            "error" in lower || "failed" in lower || "fatal" in lower ||
                "panic" in lower || "crit" in lower || "segfault" in lower -> LogSeverity.ERR
            "warn" in lower || "timeout" in lower || "retry" in lower || "denied" in lower -> LogSeverity.WARN
            else -> LogSeverity.INFO
        }
    }

    fun formatBytesPerSec(bytesPerSec: Long): String {
        return when {
            bytesPerSec < 1024L -> "${bytesPerSec} B/s"
            bytesPerSec < 1024L * 1024L -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024.0)
            else -> String.format(Locale.US, "%.2f MB/s", bytesPerSec / (1024.0 * 1024.0))
        }
    }

    fun formatBytes(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) {
            String.format(Locale.US, "%.1f GB", gb)
        } else {
            val mb = bytes / (1024.0 * 1024.0)
            String.format(Locale.US, "%.0f MB", mb)
        }
    }
}
