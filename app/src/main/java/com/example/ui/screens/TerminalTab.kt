package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.CommandSnippetEntity
import com.example.ui.components.HairlineNocCard
import com.example.ui.components.LedStatusBadge
import com.example.ui.theme.LocalNocColors
import com.example.ui.theme.NocMonoStyles
import com.example.ui.viewmodel.TerminalUiState

@Composable
fun TerminalTab(
    state: TerminalUiState,
    snippets: List<CommandSnippetEntity>,
    onInputChange: (String) -> Unit,
    onSubmitCommand: () -> Unit,
    onArrowKey: (up: Boolean) -> Unit,
    onResizePty: (cols: Int, rows: Int) -> Unit,
    onSendRaw: (String) -> Unit,
    onSendControlBytes: (ByteArray) -> Unit,
    onClearScreen: () -> Unit,
    onReconnectShell: () -> Unit,
    onInsertSnippet: (String) -> Unit,
    onRunSnippet: (String) -> Unit,
    onSaveCustomSnippet: (title: String, command: String, category: String) -> Unit,
    onDeleteSnippet: (Long) -> Unit
) {
    val noc = LocalNocColors.current
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val charLayout = remember(textMeasurer, density) {
        textMeasurer.measure(text = "M", style = NocMonoStyles.terminalLine)
    }
    var showSnippetsSheet by remember { mutableStateOf(false) }

    LaunchedEffect(state.outputLines.size) {
        if (state.outputLines.isNotEmpty()) {
            listState.scrollToItem(state.outputLines.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LedStatusBadge(
                label = when {
                    state.isConnectingShell -> "OPENING PTY"
                    state.isShellConnected -> "PTY ACTIVE"
                    else -> "PTY CLOSED"
                },
                dotColor = when {
                    state.isShellConnected -> noc.healthy
                    state.isConnectingShell -> noc.warning
                    else -> noc.critical
                },
                containerColor = when {
                    state.isShellConnected -> noc.healthyContainer
                    state.isConnectingShell -> noc.warningContainer
                    else -> noc.criticalContainer
                }
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { showSnippetsSheet = true },
                    modifier = Modifier.testTag("open_snippets_button")
                ) {
                    Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.snippets))
                }
                IconButton(
                    onClick = {
                        val allText = state.outputLines.joinToString("\n") { it.text }
                        if (allText.isNotEmpty()) {
                            clipboard.setText(AnnotatedString(allText))
                        }
                    },
                    enabled = state.outputLines.isNotEmpty()
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy terminal output",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onClearScreen) {
                    Icon(
                        imageVector = Icons.Default.ClearAll,
                        contentDescription = "Clear terminal output",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onReconnectShell) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reconnect shell",
                        tint = noc.network
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(noc.terminalBackground)
                .border(1.dp, noc.hairlineBorder, RoundedCornerShape(12.dp))
                .onSizeChanged { intSize ->
                    val cellW = charLayout.size.width.coerceAtLeast(1)
                    val cellH = charLayout.size.height.coerceAtLeast(1)
                    val padPx = with(density) { 24.dp.roundToPx() }
                    val cols = ((intSize.width - padPx) / cellW).coerceAtLeast(24)
                    val rows = ((intSize.height - padPx) / cellH).coerceAtLeast(10)
                    onResizePty(cols, rows)
                }
                .padding(12.dp)
        ) {
            if (state.outputLines.isEmpty()) {
                Text(
                    text = state.errorMessage ?: "Connecting interactive PTY shell...",
                    style = NocMonoStyles.terminalLine,
                    color = if (state.errorMessage != null) noc.critical else MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(
                        items = state.outputLines,
                        key = { index, _ -> index }
                    ) { _, line ->
                        SelectionContainer {
                            Text(
                                text = line,
                                style = NocMonoStyles.terminalLine
                            )
                        }
                    }
                }
            }
        }

        // 48dp minimum touch target accessory bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AccessoryKeyButton("Ctrl+C") { onSendControlBytes(byteArrayOf(0x03)) }
            AccessoryKeyButton("Ctrl+D") { onSendControlBytes(byteArrayOf(0x04)) }
            AccessoryKeyButton("Tab") { onSendRaw("\t") }
            AccessoryKeyButton("↑") { onArrowKey(true) }
            AccessoryKeyButton("↓") { onArrowKey(false) }
            AccessoryKeyButton("Esc") { onSendControlBytes(byteArrayOf(0x1B)) }
            AccessoryKeyButton("|") { onInputChange(state.commandInput + "|") }
            AccessoryKeyButton("/") { onInputChange(state.commandInput + "/") }
            AccessoryKeyButton("~") { onInputChange(state.commandInput + "~") }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = state.commandInput,
                onValueChange = onInputChange,
                placeholder = { Text("Enter shell command...", style = NocMonoStyles.codeLine) },
                textStyle = NocMonoStyles.codeLine,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(onSend = { onSubmitCommand() }),
                modifier = Modifier
                    .weight(1f)
                    .testTag("terminal_command_input")
            )
            Button(
                onClick = onSubmitCommand,
                modifier = Modifier
                    .height(52.dp)
                    .testTag("terminal_send_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send command"
                )
            }
        }
    }

    if (showSnippetsSheet) {
        SnippetsBottomSheet(
            snippets = snippets,
            onDismiss = { showSnippetsSheet = false },
            onInsert = { cmd ->
                onInsertSnippet(cmd)
                showSnippetsSheet = false
            },
            onRun = { cmd ->
                onRunSnippet(cmd)
                showSnippetsSheet = false
            },
            onSaveNew = onSaveCustomSnippet,
            onDelete = onDeleteSnippet
        )
    }
}

@Composable
private fun AccessoryKeyButton(
    label: String,
    onClick: () -> Unit
) {
    val noc = LocalNocColors.current
    Surface(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        color = noc.elevatedSurface,
        border = BorderStroke(1.dp, noc.hairlineBorder),
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = noc.network
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnippetsBottomSheet(
    snippets: List<CommandSnippetEntity>,
    onDismiss: () -> Unit,
    onInsert: (String) -> Unit,
    onRun: (String) -> Unit,
    onSaveNew: (title: String, command: String, category: String) -> Unit,
    onDelete: (Long) -> Unit
) {
    val noc = LocalNocColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showCreateDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Command Snippets",
                    style = MaterialTheme.typography.headlineSmall
                )
                Button(
                    onClick = { showCreateDialog = true },
                    modifier = Modifier.testTag("new_snippet_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("New")
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(380.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(snippets, key = { it.id }) { snippet ->
                    HairlineNocCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = snippet.title,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = snippet.category.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = noc.network
                                )
                            }
                            if (!snippet.isBuiltIn) {
                                IconButton(onClick = { onDelete(snippet.id) }) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Delete snippet",
                                        tint = noc.critical
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = snippet.command,
                            style = NocMonoStyles.codeLine,
                            color = noc.healthy,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onInsert(snippet.command) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.insert_snippet))
                            }
                            Button(
                                onClick = { onRun(snippet.command) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.run_snippet))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        var title by remember { mutableStateOf("") }
        var command by remember { mutableStateOf("") }
        var category by remember { mutableStateOf("Custom") }

        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("Save Command Snippet") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Title") },
                        placeholder = { Text("Tail Nginx Errors") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("Shell Command") },
                        placeholder = { Text("tail -n 50 /var/log/nginx/error.log") },
                        textStyle = NocMonoStyles.codeLine,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        label = { Text("Category") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveNew(title, command, category)
                        showCreateDialog = false
                    },
                    enabled = title.isNotBlank() && command.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
