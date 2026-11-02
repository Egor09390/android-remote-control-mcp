package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType

/** One-screen zrok2 enable-token setup; the credential is never shown again. */
@Composable
fun ZrokQuickSetupCard(
    config: ServerConfig,
    serverStatus: ServerStatus,
    onEnable: (token: String, name: String) -> Unit,
) {
    var token by remember { mutableStateOf("") }
    var name by remember(config.zrokReservedName) { mutableStateOf(config.zrokReservedName) }
    val canConfigure = serverStatus is ServerStatus.Stopped || serverStatus is ServerStatus.Error
    val configured = config.tunnelProvider == TunnelProviderType.ZROK && config.tunnelEnabled
    val tokenAvailable = config.zrokEnableToken.isNotBlank()
    val nameValid = name.isBlank() || Regex("[a-z0-9](?:[a-z0-9-]{1,38}[a-z0-9])").matches(name)

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.zrok_quick_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.zrok_quick_description),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it.trim() },
                label = { Text(stringResource(R.string.zrok_quick_token_label)) },
                placeholder = {
                    Text(
                        stringResource(
                            if (tokenAvailable) R.string.zrok_quick_saved_token
                            else R.string.zrok_quick_token_placeholder,
                        ),
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = canConfigure,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.trim() },
                label = { Text(stringResource(R.string.zrok_quick_name_label)) },
                supportingText = { Text(stringResource(R.string.zrok_quick_name_hint)) },
                isError = !nameValid,
                singleLine = true,
                enabled = canConfigure,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    onEnable(token, name)
                    token = ""
                },
                enabled = canConfigure && nameValid && (token.isNotBlank() || tokenAvailable),
            ) {
                Text(stringResource(R.string.zrok_quick_activate))
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(
                    when {
                        !canConfigure -> R.string.zrok_quick_stop_first
                        configured -> R.string.zrok_quick_configured
                        else -> R.string.zrok_quick_not_configured
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.zrok_quick_warning),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
