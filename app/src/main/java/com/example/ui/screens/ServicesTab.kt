package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.example.domain.model.ServiceAction
import com.example.domain.model.ServiceFilter
import com.example.domain.model.SystemdUnit
import com.example.ui.components.EmptyNocState
import com.example.ui.components.HairlineNocCard
import com.example.ui.components.LedStatusBadge
import com.example.ui.theme.LocalNocColors
import com.example.ui.theme.NocMonoStyles
import com.example.ui.viewmodel.ServicesUiState

@Composable
fun ServicesTab(
    state: ServicesUiState,
    onSearchChange: (String) -> Unit,
    onFilterChange: (ServiceFilter) -> Unit,
    onRefresh: () -> Unit,
    onServiceAction: (unitName: String, action: ServiceAction) -> Unit,
    onInspectLogs: (unitName: String) -> Unit,
    onInspectStatus: (unitName: String) -> Unit,
    onDismissStatus: () -> Unit
) {
    val noc = LocalNocColors.current
    var pendingConfirmation by remember { mutableStateOf<Pair<SystemdUnit, ServiceAction>?>(null) }

    val filteredUnits = remember(state.units, state.searchQuery, state.filter) {
        val query = state.searchQuery.trim().lowercase()
        state.units.filter { unit ->
            val matchesFilter = when (state.filter) {
                ServiceFilter.ALL -> true
                ServiceFilter.ACTIVE -> unit.isActive
                ServiceFilter.FAILED -> unit.isFailed
                ServiceFilter.INACTIVE -> !unit.isActive && !unit.isFailed
            }
            val matchesQuery = query.isEmpty() ||
                unit.name.lowercase().contains(query) ||
                unit.description.lowercase().contains(query)
            matchesFilter && matchesQuery
        }
    }

    val counts = remember(state.units) {
        mapOf(
            ServiceFilter.ALL to state.units.size,
            ServiceFilter.ACTIVE to state.units.count { it.isActive },
            ServiceFilter.FAILED to state.units.count { it.isFailed },
            ServiceFilter.INACTIVE to state.units.count { !it.isActive && !it.isFailed }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onSearchChange,
                    placeholder = { Text("Search systemd units (nginx, ssh, docker...)") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("services_search_input")
                )
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.testTag("refresh_services_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh services",
                        tint = noc.network
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ServiceFilter.entries.forEach { filter ->
                    val count = counts[filter] ?: 0
                    FilterChip(
                        selected = state.filter == filter,
                        onClick = { onFilterChange(filter) },
                        label = { Text("${filter.label} ($count)") },
                        modifier = Modifier.testTag("service_filter_${filter.name.lowercase()}")
                    )
                }
            }
        }

        when {
            state.isLoading && state.units.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = noc.healthy)
                }
            }
            state.errorMessage != null && state.units.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyNocState(
                        title = "Services Unavailable",
                        description = state.errorMessage,
                        actionLabel = "Retry",
                        onAction = onRefresh
                    )
                }
            }
            filteredUnits.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyNocState(
                        title = "No Matching Services",
                        description = "Try clearing the search query or switching the unit state filter."
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredUnits, key = { it.name }) { unit ->
                        ServiceUnitCard(
                            unit = unit,
                            isActionPending = state.activeActionUnit == unit.name,
                            onAction = { action ->
                                if (action.requiresConfirmation) {
                                    pendingConfirmation = unit to action
                                } else {
                                    onServiceAction(unit.name, action)
                                }
                            },
                            onInspectLogs = { onInspectLogs(unit.name) },
                            onInspectStatus = { onInspectStatus(unit.name) }
                        )
                    }
                }
            }
        }
    }

    pendingConfirmation?.let { (unit, action) ->
        AlertDialog(
            onDismissRequest = { pendingConfirmation = null },
            title = { Text("${action.displayLabel} ${unit.name}?") },
            text = {
                Text(
                    if (action == ServiceAction.STOP) {
                        "Stopping ${unit.name} may interrupt running workloads or disconnect active clients."
                    } else {
                        "Restarting ${unit.name} will briefly cycle the service process."
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onServiceAction(unit.name, action)
                        pendingConfirmation = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (action == ServiceAction.STOP) noc.critical else noc.warning,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(action.displayLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmation = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    state.statusInspectUnit?.let { unitName ->
        AlertDialog(
            onDismissRequest = onDismissStatus,
            title = { Text("Status: $unitName") },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 360.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(noc.terminalBackground)
                        .border(1.dp, noc.hairlineBorder, RoundedCornerShape(10.dp))
                        .padding(10.dp)
                ) {
                    if (state.isLoadingStatus) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = noc.healthy
                        )
                    } else {
                        SelectionContainer {
                            Text(
                                text = state.statusInspectOutput,
                                style = NocMonoStyles.codeLine,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.verticalScroll(rememberScrollState())
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissStatus) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun ServiceUnitCard(
    unit: SystemdUnit,
    isActionPending: Boolean,
    onAction: (ServiceAction) -> Unit,
    onInspectLogs: () -> Unit,
    onInspectStatus: () -> Unit
) {
    val noc = LocalNocColors.current
    val (dotColor, bgColor) = when {
        unit.isFailed -> noc.critical to noc.criticalContainer
        unit.isActive -> noc.healthy to noc.healthyContainer
        else -> noc.textMuted to noc.elevatedSurface
    }

    HairlineNocCard(
        borderColor = if (unit.isFailed) noc.critical.copy(alpha = 0.5f) else noc.hairlineBorder
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = unit.name,
                    style = NocMonoStyles.metricValue,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (unit.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = unit.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            LedStatusBadge(
                label = "${unit.activeState.uppercase()} • ${unit.subState}",
                dotColor = dotColor,
                containerColor = bgColor
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isActionPending) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                if (!unit.isActive) {
                    OutlinedButton(onClick = { onAction(ServiceAction.START) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Start")
                    }
                } else {
                    OutlinedButton(onClick = { onAction(ServiceAction.RESTART) }) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Restart")
                    }
                    OutlinedButton(
                        onClick = { onAction(ServiceAction.STOP) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = noc.critical)
                    ) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stop")
                    }
                }
                OutlinedButton(onClick = onInspectStatus) {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Status")
                }
                OutlinedButton(onClick = onInspectLogs) {
                    Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Logs")
                }
            }
        }
    }
}
