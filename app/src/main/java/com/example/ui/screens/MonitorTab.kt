package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Dangerous
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.PollingInterval
import com.example.domain.model.ProcessInfo
import com.example.ssh.LinuxTelemetryParser
import com.example.ui.components.HairlineNocCard
import com.example.ui.components.LedStatusBadge
import com.example.ui.components.RadialCpuGauge
import com.example.ui.components.SparklineChart
import com.example.ui.components.UtilizationProgressBar
import com.example.ui.theme.LocalNocColors
import com.example.ui.theme.NocMonoStyles
import com.example.ui.viewmodel.MonitorUiState
import java.util.Locale

@Composable
fun MonitorTab(
    state: MonitorUiState,
    onSelectInterval: (PollingInterval) -> Unit,
    onTogglePaused: () -> Unit,
    onRefreshNow: () -> Unit,
    onTerminateProcess: (pid: Int, force: Boolean) -> Unit
) {
    val noc = LocalNocColors.current
    val snap = state.snapshot
    var selectedProcess by remember { mutableStateOf<ProcessInfo?>(null) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWide = maxWidth >= 680.dp

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                HostSummaryBanner(
                    state = state,
                    onSelectInterval = onSelectInterval,
                    onTogglePaused = onTogglePaused,
                    onRefreshNow = onRefreshNow
                )
            }

            if (isWide) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CpuTelemetryCard(
                            state = state,
                            modifier = Modifier.weight(1f)
                        )
                        MemoryTelemetryCard(
                            state = state,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        NetworkTelemetryCard(
                            state = state,
                            modifier = Modifier.weight(1f)
                        )
                        DiskMountsCard(
                            state = state,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            } else {
                item { CpuTelemetryCard(state = state) }
                item { MemoryTelemetryCard(state = state) }
                item { NetworkTelemetryCard(state = state) }
                item { DiskMountsCard(state = state) }
            }

            item {
                TopProcessesCard(
                    processes = snap.topProcesses,
                    onSelectProcess = { proc ->
                        if (proc.pid > 1) selectedProcess = proc
                    }
                )
            }
        }
    }

    selectedProcess?.let { proc ->
        AlertDialog(
            onDismissRequest = { selectedProcess = null },
            icon = {
                Icon(
                    imageVector = Icons.Default.Dangerous,
                    contentDescription = null,
                    tint = noc.warning
                )
            },
            title = { Text("Manage Process ${proc.command}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "PID ${proc.pid} • CPU ${String.format(Locale.US, "%.1f", proc.cpuPercent)}% • RAM ${String.format(Locale.US, "%.1f", proc.memPercent)}%",
                        style = NocMonoStyles.codeLine,
                        color = noc.network
                    )
                    Text(
                        text = "Send SIGTERM for graceful shutdown or SIGKILL to force-terminate immediately.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            onTerminateProcess(proc.pid, false)
                            selectedProcess = null
                        }
                    ) {
                        Text("SIGTERM")
                    }
                    Button(
                        onClick = {
                            onTerminateProcess(proc.pid, true)
                            selectedProcess = null
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = noc.critical,
                            contentColor = MaterialTheme.colorScheme.onError
                        )
                    ) {
                        Text("SIGKILL")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedProcess = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun HostSummaryBanner(
    state: MonitorUiState,
    onSelectInterval: (PollingInterval) -> Unit,
    onTogglePaused: () -> Unit,
    onRefreshNow: () -> Unit
) {
    val noc = LocalNocColors.current
    val snap = state.snapshot

    HairlineNocCard(
        borderColor = noc.activeBorder
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = snap.hostname,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${snap.osDistro} • Kernel ${snap.kernelVersion}",
                    style = NocMonoStyles.codeLine,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = snap.uptimeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = noc.healthy
                )
            }
            LedStatusBadge(
                label = if (state.isPollingPaused) "PAUSED" else "LIVE ${state.pollingInterval.label}",
                dotColor = if (state.isPollingPaused) noc.warning else noc.healthy,
                containerColor = if (state.isPollingPaused) noc.warningContainer else noc.healthyContainer
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PollingInterval.entries.forEach { interval ->
                    FilterChip(
                        selected = state.pollingInterval == interval,
                        onClick = { onSelectInterval(interval) },
                        label = { Text(interval.label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }
            Row {
                IconButton(
                    onClick = onTogglePaused,
                    modifier = Modifier.testTag("toggle_polling_button")
                ) {
                    Icon(
                        imageVector = if (state.isPollingPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = if (state.isPollingPaused) "Resume polling" else "Pause polling",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(
                    onClick = onRefreshNow,
                    modifier = Modifier.testTag("refresh_telemetry_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh telemetry now",
                        tint = noc.network
                    )
                }
            }
        }
    }
}

@Composable
private fun CpuTelemetryCard(
    state: MonitorUiState,
    modifier: Modifier = Modifier
) {
    val noc = LocalNocColors.current
    val snap = state.snapshot

    HairlineNocCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "CPU & LOAD",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Load ${String.format(Locale.US, "%.2f", snap.load1m)} / ${String.format(Locale.US, "%.2f", snap.load5m)} / ${String.format(Locale.US, "%.2f", snap.load15m)}",
                style = MaterialTheme.typography.labelSmall,
                color = noc.network
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            RadialCpuGauge(percent = snap.cpuTotalPercent)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Utilization Trend",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                SparklineChart(
                    values = state.cpuHistory.ifEmpty { listOf(snap.cpuTotalPercent) },
                    lineColor = noc.healthy,
                    maxValueOverride = 100f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(74.dp)
                )
            }
        }

        if (snap.perCoreUsage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "${snap.perCoreUsage.size} CPU CORES",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                snap.perCoreUsage.chunked(2).forEachIndexed { rowIdx, pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        pair.forEachIndexed { colIdx, pct ->
                            val coreNumber = rowIdx * 2 + colIdx
                            val barColor = when {
                                pct >= 90f -> noc.critical
                                pct >= 70f -> noc.warning
                                else -> noc.healthy
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "core$coreNumber",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${pct.toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(modifier = Modifier.height(3.dp))
                                UtilizationProgressBar(
                                    fraction = pct / 100f,
                                    color = barColor,
                                    height = 5.dp
                                )
                            }
                        }
                        if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryTelemetryCard(
    state: MonitorUiState,
    modifier: Modifier = Modifier
) {
    val noc = LocalNocColors.current
    val snap = state.snapshot
    val memPct = if (snap.memTotalMb > 0) (snap.memUsedMb.toFloat() / snap.memTotalMb.toFloat()) else 0f
    val swapPct = if (snap.swapTotalMb > 0) (snap.swapUsedMb.toFloat() / snap.swapTotalMb.toFloat()) else 0f

    HairlineNocCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Memory,
                    contentDescription = null,
                    tint = noc.network,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "MEMORY & SWAP",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "${(memPct * 100).toInt()}%",
                style = NocMonoStyles.metricValue,
                color = if (memPct >= 0.85f) noc.warning else noc.network
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${snap.memUsedMb} MB used",
                style = NocMonoStyles.codeLine,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "${snap.memTotalMb} MB total",
                style = NocMonoStyles.codeLine,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        UtilizationProgressBar(
            fraction = memPct,
            color = if (memPct >= 0.9f) noc.critical else noc.network,
            height = 9.dp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Buffers/Cache: ${snap.memCachedMb} MB",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Swap",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (snap.swapTotalMb > 0) "${snap.swapUsedMb} / ${snap.swapTotalMb} MB" else "Disabled",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(5.dp))
        UtilizationProgressBar(
            fraction = swapPct,
            color = noc.warning,
            height = 6.dp
        )
    }
}

@Composable
private fun NetworkTelemetryCard(
    state: MonitorUiState,
    modifier: Modifier = Modifier
) {
    val noc = LocalNocColors.current
    val snap = state.snapshot

    HairlineNocCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "NETWORK I/O (${snap.netInterface})",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowDownward,
                    contentDescription = "Inbound",
                    tint = noc.healthy,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Column {
                    Text("RX IN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = LinuxTelemetryParser.formatBytesPerSec(snap.netRxBytesPerSec),
                        style = NocMonoStyles.metricValue,
                        color = noc.healthy
                    )
                }
            }
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowUpward,
                    contentDescription = "Outbound",
                    tint = noc.network,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Column {
                    Text("TX OUT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = LinuxTelemetryParser.formatBytesPerSec(snap.netTxBytesPerSec),
                        style = NocMonoStyles.metricValue,
                        color = noc.network
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SparklineChart(
            values = state.netRxHistory.map { it.toFloat() }.ifEmpty { listOf(0f) },
            lineColor = noc.healthy,
            secondaryValues = state.netTxHistory.map { it.toFloat() },
            secondaryColor = noc.network,
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
        )
    }
}

@Composable
private fun DiskMountsCard(
    state: MonitorUiState,
    modifier: Modifier = Modifier
) {
    val noc = LocalNocColors.current
    val disks = state.snapshot.disks

    HairlineNocCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Storage,
                contentDescription = null,
                tint = noc.warning,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "STORAGE MOUNTS",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (disks.isEmpty()) {
            Text(
                text = "Waiting for disk telemetry...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                disks.forEach { disk ->
                    val barColor = when {
                        disk.usagePercent >= 90f -> noc.critical
                        disk.usagePercent >= 75f -> noc.warning
                        else -> noc.healthy
                    }
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = disk.mountPoint,
                                style = NocMonoStyles.codeLine,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${LinuxTelemetryParser.formatBytes(disk.usedBytes)} / ${LinuxTelemetryParser.formatBytes(disk.totalBytes)} (${disk.usagePercent.toInt()}%)",
                                style = MaterialTheme.typography.labelSmall,
                                color = barColor
                            )
                        }
                        Spacer(modifier = Modifier.height(5.dp))
                        UtilizationProgressBar(
                            fraction = disk.usagePercent / 100f,
                            color = barColor,
                            height = 6.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopProcessesCard(
    processes: List<ProcessInfo>,
    onSelectProcess: (ProcessInfo) -> Unit
) {
    val noc = LocalNocColors.current
    HairlineNocCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "TOP PROCESSES (TAP TO SIGNAL)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(10.dp))

        if (processes.isEmpty()) {
            Text(
                text = "Collecting process snapshot...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
            ) {
                Text("PID", style = MaterialTheme.typography.labelSmall, color = noc.textMuted, modifier = Modifier.width(64.dp))
                Text("COMMAND", style = MaterialTheme.typography.labelSmall, color = noc.textMuted, modifier = Modifier.weight(1f))
                Text("CPU", style = MaterialTheme.typography.labelSmall, color = noc.textMuted, modifier = Modifier.width(56.dp))
                Text("MEM", style = MaterialTheme.typography.labelSmall, color = noc.textMuted, modifier = Modifier.width(52.dp))
            }
            HorizontalDivider(color = noc.hairlineBorder)
            processes.forEach { proc ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onSelectProcess(proc) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = proc.pid.toString(),
                        style = NocMonoStyles.codeLine,
                        color = noc.network,
                        modifier = Modifier.width(64.dp)
                    )
                    Text(
                        text = proc.command,
                        style = NocMonoStyles.codeLine,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${String.format(Locale.US, "%.1f", proc.cpuPercent)}%",
                        style = NocMonoStyles.codeLine,
                        color = if (proc.cpuPercent >= 50f) noc.warning else noc.healthy,
                        modifier = Modifier.width(56.dp)
                    )
                    Text(
                        text = "${String.format(Locale.US, "%.1f", proc.memPercent)}%",
                        style = NocMonoStyles.codeLine,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(52.dp)
                    )
                }
                HorizontalDivider(color = noc.hairlineBorder.copy(alpha = 0.4f))
            }
        }
    }
}
