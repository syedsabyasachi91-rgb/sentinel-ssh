package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.LogEntry
import com.example.domain.model.LogSeverity
import com.example.domain.model.SystemdUnit
import com.example.ui.components.EmptyNocState
import com.example.ui.components.LedStatusBadge
import com.example.ui.theme.LocalNocColors
import com.example.ui.theme.NocMonoStyles
import com.example.ui.viewmodel.LogsUiState

@Composable
fun LogsTab(
    state: LogsUiState,
    availableUnits: List<SystemdUnit>,
    onUnitSelected: (String?) -> Unit,
    onSeveritySelected: (LogSeverity) -> Unit,
    onSearchChange: (String) -> Unit,
    onToggleFollow: () -> Unit,
    onClearBuffer: () -> Unit
) {
    val noc = LocalNocColors.current
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    var unitMenuOpen by remember { mutableStateOf(false) }
    var inspectedEntry by remember { mutableStateOf<LogEntry?>(null) }

    val filteredEntries = remember(state.entries, state.searchQuery) {
        val query = state.searchQuery.trim().lowercase()
        if (query.isEmpty()) state.entries
        else state.entries.filter {
            it.message.lowercase().contains(query) || it.unit.lowercase().contains(query)
        }
    }

    LaunchedEffect(filteredEntries.size, state.isFollowing) {
        if (state.isFollowing && filteredEntries.isNotEmpty()) {
            listState.scrollToItem(filteredEntries.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { unitMenuOpen = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("logs_unit_selector")
                    ) {
                        Text(
                            text = state.selectedUnit ?: "All System Logs",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Select unit")
                    }
                    DropdownMenu(
                        expanded = unitMenuOpen,
                        onDismissRequest = { unitMenuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("All System Logs") },
                            onClick = {
                                unitMenuOpen = false
                                onUnitSelected(null)
                            }
                        )
                        availableUnits.take(30).forEach { unit ->
                            DropdownMenuItem(
                                text = { Text(unit.name, style = NocMonoStyles.codeLine) },
                                onClick = {
                                    unitMenuOpen = false
                                    onUnitSelected(unit.name)
                                }
                            )
                        }
                    }
                }

                LedStatusBadge(
                    label = if (state.isStreaming) "TAIL" else "IDLE",
                    dotColor = if (state.isStreaming) noc.healthy else noc.textMuted,
                    containerColor = if (state.isStreaming) noc.healthyContainer else noc.elevatedSurface
                )

                IconButton(
                    onClick = onToggleFollow,
                    modifier = Modifier.testTag("logs_follow_toggle")
                ) {
                    Icon(
                        imageVector = if (state.isFollowing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (state.isFollowing) "Pause log stream" else "Follow log stream",
                        tint = if (state.isFollowing) noc.healthy else noc.warning
                    )
                }
                IconButton(
                    onClick = onClearBuffer,
                    modifier = Modifier.testTag("logs_clear_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.ClearAll,
                        contentDescription = "Clear log view",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LogSeverity.entries.forEach { severity ->
                    FilterChip(
                        selected = state.severity == severity,
                        onClick = { onSeveritySelected(severity) },
                        label = { Text(severity.label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("Filter log stream...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("logs_search_input")
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(noc.terminalBackground)
                .border(1.dp, noc.hairlineBorder, RoundedCornerShape(12.dp))
        ) {
            when {
                state.errorMessage != null && filteredEntries.isEmpty() -> {
                    EmptyNocState(
                        title = "Log Stream Notice",
                        description = state.errorMessage,
                        actionLabel = "Retry Stream",
                        onAction = onToggleFollow
                    )
                }
                filteredEntries.isEmpty() -> {
                    EmptyNocState(
                        title = "Awaiting Journal Events",
                        description = "Live journalctl output will appear here as events arrive from your VPS."
                    )
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filteredEntries, key = { it.id }) { entry ->
                            LogRow(
                                entry = entry,
                                onClick = { inspectedEntry = entry }
                            )
                        }
                    }
                }
            }
        }
    }

    inspectedEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { inspectedEntry = null },
            title = { Text("${entry.unit} • ${entry.timestamp}") },
            text = {
                SelectionContainer {
                    Text(
                        text = entry.rawLine,
                        style = NocMonoStyles.codeLine,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(entry.rawLine))
                        inspectedEntry = null
                    }
                ) {
                    Text("Copy Line")
                }
            },
            dismissButton = {
                TextButton(onClick = { inspectedEntry = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun LogRow(
    entry: LogEntry,
    onClick: () -> Unit
) {
    val noc = LocalNocColors.current
    val msgColor = when (entry.severity) {
        LogSeverity.ERR -> noc.critical
        LogSeverity.WARN -> noc.warning
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = entry.timestamp,
            style = NocMonoStyles.codeLine,
            color = noc.textMuted
        )
        Text(
            text = entry.unit,
            style = NocMonoStyles.codeLine,
            color = noc.network,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(88.dp)
        )
        Text(
            text = entry.message,
            style = NocMonoStyles.codeLine,
            color = msgColor,
            modifier = Modifier.weight(1f)
        )
    }
}
