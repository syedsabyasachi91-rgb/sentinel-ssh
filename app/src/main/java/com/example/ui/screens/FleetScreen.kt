package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockReset
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.local.AuthType
import com.example.data.local.ServerProfileEntity
import com.example.ui.components.EmptyNocState
import com.example.ui.components.HairlineNocCard
import com.example.ui.components.LedStatusBadge
import com.example.ui.theme.LocalNocColors
import com.example.ui.theme.NocMonoStyles
import com.example.ui.viewmodel.FleetViewModel
import com.example.ui.viewmodel.ServerFormState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FleetScreen(
    viewModel: FleetViewModel,
    onConnectServer: (Long) -> Unit
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val formState by viewModel.formState.collectAsStateWithLifecycle()
    val noc = LocalNocColors.current
    var pendingDeleteServer by remember { mutableStateOf<ServerProfileEntity?>(null) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = noc.voidBackground,
        contentWindowInsets = WindowInsets.safeDrawing,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { viewModel.openAddServerForm() },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_server_fab")
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = stringResource(R.string.add_server))
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            noc.healthy.copy(alpha = 0.08f),
                            noc.voidBackground
                        )
                    )
                )
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                Spacer(modifier = Modifier.height(12.dp))
                FleetHeader(nodeCount = servers.size)
                Spacer(modifier = Modifier.height(16.dp))

                if (servers.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        EmptyNocState(
                            title = stringResource(R.string.empty_fleet_title),
                            description = stringResource(R.string.empty_fleet_desc),
                            actionLabel = stringResource(R.string.add_server),
                            onAction = { viewModel.openAddServerForm() },
                            actionTestTag = "empty_add_server_button",
                            modifier = Modifier.widthIn(max = 460.dp)
                        )
                    }
                } else {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val columns = if (maxWidth >= 680.dp) 2 else 1
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns),
                            contentPadding = PaddingValues(bottom = 96.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(servers, key = { it.id }) { server ->
                                ServerNodeCard(
                                    server = server,
                                    onConnect = { onConnectServer(server.id) },
                                    onEdit = { viewModel.openEditServerForm(server) },
                                    onResetHostKey = { viewModel.resetTrustedHostKey(server.id) },
                                    onRequestDelete = { pendingDeleteServer = server }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (formState.isOpen) {
        AddEditServerBottomSheet(
            state = formState,
            onDismiss = viewModel::closeForm,
            onAliasChange = viewModel::updateAlias,
            onHostChange = viewModel::updateHost,
            onPortChange = viewModel::updatePort,
            onUsernameChange = viewModel::updateUsername,
            onAuthTypeChange = viewModel::updateAuthType,
            onSecretChange = viewModel::updateSecret,
            onPassphraseChange = viewModel::updatePassphrase,
            onImportKeyUri = viewModel::importPrivateKeyFromUri,
            onSave = { viewModel.saveServer() }
        )
    }

    pendingDeleteServer?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteServer = null },
            title = { Text("Remove ${target.alias}?") },
            text = {
                Text("This permanently deletes the encrypted SSH credentials and trusted host fingerprint for ${target.host} from this device.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteServer(target.id)
                        pendingDeleteServer = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = noc.critical,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.delete_server))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteServer = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun FleetHeader(nodeCount: Int) {
    val noc = LocalNocColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(noc.healthyContainer)
                    .border(1.dp, noc.healthy.copy(alpha = 0.4f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Terminal,
                    contentDescription = "Sentinel Ssh logo",
                    tint = noc.healthy,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.fleet_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = stringResource(R.string.fleet_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        LedStatusBadge(
            label = "$nodeCount ${if (nodeCount == 1) "NODE" else "NODES"}",
            dotColor = if (nodeCount > 0) noc.healthy else noc.textMuted,
            containerColor = if (nodeCount > 0) noc.healthyContainer else noc.elevatedSurface
        )
    }
}

@Composable
private fun ServerNodeCard(
    server: ServerProfileEntity,
    onConnect: () -> Unit,
    onEdit: () -> Unit,
    onResetHostKey: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val noc = LocalNocColors.current
    var menuExpanded by remember { mutableStateOf(false) }

    HairlineNocCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("server_card_${server.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(noc.elevatedSurface)
                        .border(1.dp, noc.hairlineBorder, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = null,
                        tint = noc.network,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = server.alias,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${server.username}@${server.host}:${server.port}",
                        style = NocMonoStyles.codeLine,
                        color = noc.network,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.testTag("server_menu_${server.id}")
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Server options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEdit()
                        }
                    )
                    if (server.trustedHostFingerprint != null) {
                        DropdownMenuItem(
                            text = { Text("Reset Host Key") },
                            leadingIcon = { Icon(Icons.Default.LockReset, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onResetHostKey()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Delete", color = noc.critical) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = null,
                                tint = noc.critical
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onRequestDelete()
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LedStatusBadge(
                label = if (server.authType == AuthType.PRIVATE_KEY) "SSH KEY" else "PASSWORD",
                dotColor = noc.network,
                containerColor = noc.networkContainer
            )
            if (server.trustedHostFingerprint != null) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(noc.healthyContainer)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.VerifiedUser,
                        contentDescription = "Host key verified",
                        tint = noc.healthy,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = "TOFU",
                        style = MaterialTheme.typography.labelSmall,
                        color = noc.healthy
                    )
                }
            }
        }

        if (!server.lastOsSummary.isNullOrBlank() || server.lastConnectedAt != null) {
            Spacer(modifier = Modifier.height(10.dp))
            val timeText = server.lastConnectedAt?.let { ts ->
                SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ts))
            }
            val metaLine = listOfNotNull(server.lastOsSummary, timeText?.let { "Last: $it" })
                .joinToString(" • ")
            Text(
                text = metaLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        Button(
            onClick = onConnect,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("connect_server_${server.id}")
        ) {
            Text(text = stringResource(R.string.connect))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEditServerBottomSheet(
    state: ServerFormState,
    onDismiss: () -> Unit,
    onAliasChange: (String) -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onAuthTypeChange: (AuthType) -> Unit,
    onSecretChange: (String) -> Unit,
    onPassphraseChange: (String) -> Unit,
    onImportKeyUri: (android.net.Uri) -> Unit,
    onSave: () -> Unit
) {
    val noc = LocalNocColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showPassword by remember { mutableStateOf(false) }
    val keyPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onImportKeyUri(uri)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = if (state.editingServerId != null) {
                    stringResource(R.string.edit_server)
                } else {
                    stringResource(R.string.add_server)
                },
                style = MaterialTheme.typography.headlineSmall
            )

            OutlinedTextField(
                value = state.alias,
                onValueChange = onAliasChange,
                label = { Text("Server Label (optional)") },
                placeholder = { Text("prod-api-eu-1") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("server_alias_input")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = state.host,
                    onValueChange = onHostChange,
                    label = { Text("Host / IP") },
                    placeholder = { Text("203.0.113.10") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier
                        .weight(0.72f)
                        .testTag("server_host_input")
                )
                OutlinedTextField(
                    value = state.port,
                    onValueChange = onPortChange,
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next
                    ),
                    modifier = Modifier
                        .weight(0.28f)
                        .testTag("server_port_input")
                )
            }

            OutlinedTextField(
                value = state.username,
                onValueChange = onUsernameChange,
                label = { Text("SSH Username") },
                placeholder = { Text("root") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("server_username_input")
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilterChip(
                    selected = state.authType == AuthType.PASSWORD,
                    onClick = { onAuthTypeChange(AuthType.PASSWORD) },
                    label = { Text("Password") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.testTag("auth_password_chip")
                )
                FilterChip(
                    selected = state.authType == AuthType.PRIVATE_KEY,
                    onClick = { onAuthTypeChange(AuthType.PRIVATE_KEY) },
                    label = { Text("Private Key") },
                    leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    modifier = Modifier.testTag("auth_key_chip")
                )
            }

            if (state.authType == AuthType.PASSWORD) {
                OutlinedTextField(
                    value = state.secret,
                    onValueChange = onSecretChange,
                    label = {
                        Text(
                            if (state.hasExistingSecret) "SSH Password (leave blank to keep current)"
                            else "SSH Password"
                        )
                    },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = "Toggle password visibility"
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("server_password_input")
                )
            } else {
                OutlinedButton(
                    onClick = { keyPickerLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("import_key_file_button")
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = state.importedKeyName?.let { "Loaded: $it" }
                            ?: stringResource(R.string.import_key_file)
                    )
                }

                OutlinedTextField(
                    value = state.secret,
                    onValueChange = onSecretChange,
                    label = {
                        Text(
                            if (state.hasExistingSecret) "Private Key PEM (blank keeps existing)"
                            else "Private Key (PEM / OpenSSH)"
                        )
                    },
                    placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                    minLines = 3,
                    maxLines = 5,
                    textStyle = NocMonoStyles.codeLine,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("server_private_key_input")
                )

                OutlinedTextField(
                    value = state.passphrase,
                    onValueChange = onPassphraseChange,
                    label = { Text("Key Passphrase (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            state.errorMessage?.let { err ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(noc.criticalContainer)
                        .border(1.dp, noc.critical.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = noc.critical
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = onSave,
                    enabled = !state.isSaving,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("save_server_button")
                ) {
                    Text(if (state.isSaving) "Encrypting..." else stringResource(R.string.save))
                }
            }
        }
    }
}
