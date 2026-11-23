package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GppMaybe
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.model.ConnectionState
import com.example.ui.theme.LocalNocColors
import com.example.ui.theme.NocMonoStyles

@Composable
fun HostKeyVerificationDialog(
    state: ConnectionState.AwaitingHostKeyApproval,
    onTrust: () -> Unit,
    onReject: () -> Unit
) {
    val noc = LocalNocColors.current
    val accent = if (state.isChanged) noc.critical else noc.network

    AlertDialog(
        onDismissRequest = onReject,
        icon = {
            Icon(
                imageVector = if (state.isChanged) Icons.Default.GppMaybe else Icons.Default.VerifiedUser,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = if (state.isChanged) "Host Key Changed!" else stringResource(R.string.verify_host_key_title)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.isChanged) {
                    Text(
                        text = stringResource(R.string.host_key_changed_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = noc.critical
                    )
                } else {
                    Text(
                        text = "Confirm the SSH host key fingerprint for ${state.host}:${state.port} before sending credentials:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                SelectionContainer {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(noc.terminalBackground)
                            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = "SHA-256 FINGERPRINT",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = state.fingerprintSha256,
                            style = NocMonoStyles.codeLine,
                            color = accent
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onTrust,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.isChanged) noc.critical else MaterialTheme.colorScheme.primary,
                    contentColor = if (state.isChanged) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onPrimary
                ),
                modifier = Modifier.testTag("trust_host_key_button")
            ) {
                Text(stringResource(R.string.trust_and_connect))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onReject,
                modifier = Modifier.testTag("reject_host_key_button")
            ) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
