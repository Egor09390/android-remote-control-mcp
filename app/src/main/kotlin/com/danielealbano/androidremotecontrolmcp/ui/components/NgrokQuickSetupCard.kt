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

/**
 * First-screen, VPS-free setup for the built-in ngrok Android agent.
 * Never embed the user authtoken in source, APK resources, GitHub, or a shared link.
 * Only the current device's existing settings repository receives the secret.
 */
@Composable
fun NgrokQuickSetupCard(
    config: ServerConfig,
    serverStatus: ServerStatus,
    onEnable: (String) -> Unit,
) {
    var token by remember { mutableStateOf("") }
    val canConfigure = serverStatus is ServerStatus.Stopped || serverStatus is ServerStatus.Error
    val savedToken = config.ngrokAuthtoken.isNotBlank()
    val configured = config.tunnelEnabled && config.tunnelProvider == TunnelProviderType.NGROK

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.ngrok_quick_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.ngrok_quick_description),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it.trim() },
                label = { Text(stringResource(R.string.ngrok_quick_token_label)) },
                placeholder = {
                    Text(
                        stringResource(
                            if (savedToken) R.string.ngrok_quick_saved_token
                            else R.string.ngrok_quick_token_placeholder,
                        ),
                    )
                },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = canConfigure,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    onEnable(token)
                    token = ""
                },
                enabled = canConfigure && (token.isNotBlank() || savedToken),
            ) {
                Text(stringResource(R.string.ngrok_quick_activate))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    when {
                        !canConfigure -> R.string.ngrok_quick_stop_first
                        configured -> R.string.ngrok_quick_configured
                        else -> R.string.ngrok_quick_not_configured
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(R.string.ngrok_quick_artemis_note),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
