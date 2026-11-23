package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MiscellaneousServices
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.MiscellaneousServices
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.ConnectionState
import com.example.domain.model.WorkspaceTab
import com.example.ui.components.EmptyNocState
import com.example.ui.components.HairlineNocCard
import com.example.ui.components.LedStatusBadge
import com.example.ui.theme.LocalNocColors
import com.example.ui.theme.NocMonoStyles
import com.example.ui.viewmodel.PendingSudoAction
import com.example.ui.viewmodel.WorkspaceViewModel

private data class WorkspaceNavItem(
    val tab: WorkspaceTab,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

private val workspaceNavItems = listOf(
    WorkspaceNavItem(
        tab = WorkspaceTab.MONITOR,
        labelRes = R.string.nav_monitor,
        selectedIcon = Icons.Filled.MonitorHeart,
        unselectedIcon = Icons.Outlined.MonitorHeart
    ),
    WorkspaceNavItem(
        tab = WorkspaceTab.SERVICES,
        labelRes = R.string.nav_services,
        selectedIcon = Icons.Filled.MiscellaneousServices,
        unselectedIcon = Icons.Outlined.MiscellaneousServices
    ),
    WorkspaceNavItem(
        tab = WorkspaceTab.LOGS,
        labelRes = R.string.nav_logs,
        selectedIcon = Icons.AutoMirrored.Filled.Article,
        unselectedIcon = Icons.AutoMirrored.Outlined.Article
    ),
    WorkspaceNavItem(
        tab = WorkspaceTab.TERMINAL,
        labelRes = R.string.nav_terminal,
        selectedIcon = Icons.Filled.Terminal,
        unselectedIcon = Icons.Outlined.Terminal
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(
    viewModel: WorkspaceViewModel,
    onNavigateBack: () -> Unit
) {
    val profile by viewModel.serverProfile.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
    val monitorState by viewModel.monitorState.collectAsStateWithLifecycle()
    val servicesState by viewModel.servicesState.collectAsStateWithLifecycle()
    val logsState by viewModel.logsState.collectAsStateWithLifecycle()
    val terminalState by viewModel.terminalState.collectAsStateWithLifecycle()
    val snippets by viewModel.snippets.collectAsStateWithLifecycle()
    val pendingSudoAction by viewModel.pendingSudoAction.collectAsStateWithLifecycle()
    val snackbarMessage by viewModel.snackbarMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val noc = LocalNocColors.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setAppForeground(true)
                Lifecycle.Event.ON_STOP -> viewModel.setAppForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(snackbarMessage) {
        val msg = snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.dismissSnackbar()
    }

    BackHandler {
        viewModel.disconnect()
        onNavigateBack()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 720.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = noc.voidBackground,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                viewModel.disconnect()
                                onNavigateBack()
                            },
                            modifier = Modifier.testTag("workspace_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Disconnect and return to Fleet"
                            )
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = profile?.alias ?: "VPS Node",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            profile?.let {
                                Text(
                                    text = "${it.username}@${it.host}:${it.port}",
                                    style = NocMonoStyles.codeLine,
                                    color = noc.network,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                    actions = {
                        val (badgeLabel, dotColor, bgColor) = when (connectionState) {
                            is ConnectionState.Connected -> Triple("ONLINE", noc.healthy, noc.healthyContainer)
                            is ConnectionState.Connecting -> Triple("SYNC", noc.warning, noc.warningContainer)
                            is ConnectionState.AwaitingHostKeyApproval -> Triple("VERIFY", noc.network, noc.networkContainer)
                            is ConnectionState.Error -> Triple("ERROR", noc.critical, noc.criticalContainer)
                            is ConnectionState.Disconnected -> Triple("OFFLINE", noc.textMuted, noc.elevatedSurface)
                        }
                        LedStatusBadge(
                            label = badgeLabel,
                            dotColor = dotColor,
                            containerColor = bgColor,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        if (connectionState is ConnectionState.Connected) {
                            IconButton(
                                onClick = { viewModel.disconnect() },
                                modifier = Modifier.testTag("workspace_disconnect_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LinkOff,
                                    contentDescription = stringResource(R.string.disconnect),
                                    tint = noc.textMuted
                                )
                            }
                        } else if (connectionState is ConnectionState.Error || connectionState is ConnectionState.Disconnected) {
                            IconButton(
                                onClick = { viewModel.connect() },
                                modifier = Modifier.testTag("workspace_reconnect_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.reconnect),
                                    tint = noc.network
                                )
                            }
                        }
                    }
                )
            },
            bottomBar = {
                if (!useNavRail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        workspaceNavItems.forEach { item ->
                            val selected = selectedTab == item.tab
                            val label = stringResource(item.labelRes)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { viewModel.selectTab(item.tab) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag("nav_tab_${item.tab.name.lowercase()}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        Spacer(modifier = Modifier.height(12.dp))
                        workspaceNavItems.forEach { item ->
                            val selected = selectedTab == item.tab
                            val label = stringResource(item.labelRes)
                            NavigationRailItem(
                                selected = selected,
                                onClick = { viewModel.selectTab(item.tab) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag("nav_rail_${item.tab.name.lowercase()}")
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(noc.voidBackground)
                ) {
                    when (val conn = connectionState) {
                        is ConnectionState.Connecting,
                        is ConnectionState.AwaitingHostKeyApproval -> {
                            val stepText = (conn as? ConnectionState.Connecting)?.step
                                ?: "Awaiting SSH host key verification..."
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(
                                    color = noc.healthy,
                                    modifier = Modifier.size(44.dp)
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = stepText,
                                    style = NocMonoStyles.codeLine,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        is ConnectionState.Error -> {
                            val clipboard = LocalClipboardManager.current
                            var showDiagnostics by remember(conn) { mutableStateOf(false) }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    modifier = Modifier
                                        .widthIn(max = 480.dp)
                                        .padding(24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    EmptyNocState(
                                        title = "SSH Connection Failed",
                                        description = conn.message
                                    )
                                    if (!conn.technicalDetails.isNullOrBlank()) {
                                        TextButton(
                                            onClick = { showDiagnostics = !showDiagnostics },
                                            modifier = Modifier
                                                .heightIn(min = 48.dp)
                                                .testTag("toggle_ssh_diagnostics_button")
                                        ) {
                                            Icon(
                                                imageVector = if (showDiagnostics) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Text(
                                                text = if (showDiagnostics) "Hide Diagnostic Details" else "Diagnostic Details",
                                                modifier = Modifier.padding(start = 6.dp)
                                            )
                                        }
                                        if (showDiagnostics) {
                                            HairlineNocCard(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(bottom = 16.dp),
                                                containerColor = noc.terminalBackground,
                                                borderColor = noc.critical.copy(alpha = 0.4f)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "HANDSHAKE DIAGNOSTICS",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = noc.critical
                                                    )
                                                    IconButton(
                                                        onClick = {
                                                            clipboard.setText(AnnotatedString(conn.technicalDetails))
                                                        },
                                                        modifier = Modifier.testTag("copy_ssh_diagnostics_button")
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.ContentCopy,
                                                            contentDescription = "Copy diagnostic details",
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                                SelectionContainer {
                                                    Text(
                                                        text = conn.technicalDetails,
                                                        style = NocMonoStyles.codeLine,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        OutlinedButton(onClick = {
                                            viewModel.disconnect()
                                            onNavigateBack()
                                        }) {
                                            Text("Back to Fleet")
                                        }
                                        Button(onClick = { viewModel.connect() }) {
                                            Text(stringResource(R.string.reconnect))
                                        }
                                    }
                                }
                            }
                        }

                        is ConnectionState.Disconnected -> {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                EmptyNocState(
                                    title = "Session Disconnected",
                                    description = "The SSH tunnel to this VPS is closed.",
                                    actionLabel = stringResource(R.string.reconnect),
                                    onAction = { viewModel.connect() }
                                )
                            }
                        }

                        is ConnectionState.Connected -> {
                            when (selectedTab) {
                                WorkspaceTab.MONITOR -> MonitorTab(
                                    state = monitorState,
                                    onSelectInterval = viewModel::setPollingInterval,
                                    onTogglePaused = viewModel::togglePollingPaused,
                                    onRefreshNow = viewModel::refreshTelemetryNow,
                                    onTerminateProcess = { pid, force ->
                                        viewModel.terminateProcess(pid, force)
                                    }
                                )
                                WorkspaceTab.SERVICES -> ServicesTab(
                                    state = servicesState,
                                    onSearchChange = viewModel::updateServiceSearch,
                                    onFilterChange = viewModel::updateServiceFilter,
                                    onRefresh = viewModel::refreshServices,
                                    onServiceAction = { unitName, action ->
                                        viewModel.controlService(unitName, action)
                                    },
                                    onInspectLogs = viewModel::inspectUnitInLogs,
                                    onInspectStatus = viewModel::inspectServiceStatus,
                                    onDismissStatus = viewModel::dismissServiceStatus
                                )
                                WorkspaceTab.LOGS -> LogsTab(
                                    state = logsState,
                                    availableUnits = servicesState.units,
                                    onUnitSelected = viewModel::updateLogUnitFilter,
                                    onSeveritySelected = viewModel::updateLogSeverity,
                                    onSearchChange = viewModel::updateLogSearch,
                                    onToggleFollow = viewModel::toggleLogFollow,
                                    onClearBuffer = viewModel::clearLogBuffer
                                )
                                WorkspaceTab.TERMINAL -> TerminalTab(
                                    state = terminalState,
                                    snippets = snippets,
                                    onInputChange = viewModel::updateTerminalInput,
                                    onSubmitCommand = viewModel::submitTerminalCommand,
                                    onArrowKey = viewModel::handleArrowKey,
                                    onResizePty = viewModel::resizeTerminalPty,
                                    onSendRaw = viewModel::sendRawToShell,
                                    onSendControlBytes = viewModel::sendControlBytesToShell,
                                    onClearScreen = viewModel::clearTerminalScreen,
                                    onReconnectShell = viewModel::connectTerminalShell,
                                    onInsertSnippet = viewModel::insertSnippet,
                                    onRunSnippet = viewModel::runSnippet,
                                    onSaveCustomSnippet = viewModel::saveCustomSnippet,
                                    onDeleteSnippet = viewModel::deleteSnippet
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    val hostApproval = connectionState as? ConnectionState.AwaitingHostKeyApproval
    if (hostApproval != null) {
        HostKeyVerificationDialog(
            state = hostApproval,
            onTrust = { viewModel.respondToHostKey(trust = true) },
            onReject = {
                viewModel.respondToHostKey(trust = false)
                onNavigateBack()
            }
        )
    }

    pendingSudoAction?.let { pending ->
        SudoPasswordPromptDialog(
            pending = pending,
            onSubmit = viewModel::submitSudoPassword,
            onDismiss = viewModel::dismissSudoPrompt
        )
    }
}

@Composable
private fun SudoPasswordPromptDialog(
    pending: PendingSudoAction,
    onSubmit: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val noc = LocalNocColors.current
    var sudoPassword by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = {
            sudoPassword = ""
            onDismiss()
        },
        icon = {
            Icon(
                imageVector = Icons.Default.AdminPanelSettings,
                contentDescription = null,
                tint = noc.warning
            )
        },
        title = { Text("Sudo Password Required") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Enter your sudo password to execute: ${pending.description}. It is streamed only to this command and never saved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = sudoPassword,
                    onValueChange = { sudoPassword = it },
                    label = { Text("Sudo password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("sudo_password_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val value = sudoPassword
                    sudoPassword = ""
                    onSubmit(value)
                },
                enabled = sudoPassword.isNotEmpty(),
                modifier = Modifier.testTag("sudo_submit_button")
            ) {
                Text("Authorize")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    sudoPassword = ""
                    onDismiss()
                }
            ) {
                Text("Cancel")
            }
        }
    )
}
