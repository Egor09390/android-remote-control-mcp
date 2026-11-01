package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.services.tunnel.OwnVpsInstaller
import kotlinx.coroutines.launch

/** Always visible on the first Server tab, above MCP start controls. */
@Composable
fun OwnVpsSetupCard(
    config: ServerConfig,
    onConfigured: (String, String) -> Unit,
    onArtemisPortsChanged: (Int, Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var sshPort by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }
    var domain by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    var adbPort by remember(config.artemisAdbPort) {
        mutableStateOf(config.artemisAdbPort.takeIf { it > 0 }?.toString().orEmpty())
    }
    var pairingPort by remember(config.artemisPairingPort) {
        mutableStateOf(config.artemisPairingPort.takeIf { it > 0 }?.toString().orEmpty())
    }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }

    LaunchedEffect(config.selfHostedTunnelUrl) {
        if (domain.isBlank()) domain = config.selfHostedTunnelUrl.removePrefix("https://")
    }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.selfhost_card_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.selfhost_note),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = host, onValueChange = { host = it },
                label = { Text(stringResource(R.string.selfhost_ip)) },
                singleLine = true, enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = sshPort, onValueChange = { sshPort = it },
                label = { Text(stringResource(R.string.selfhost_port)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = username, onValueChange = { username = it },
                label = { Text(stringResource(R.string.selfhost_user)) },
                singleLine = true, enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text(stringResource(R.string.selfhost_password)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true, enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = domain, onValueChange = { domain = it },
                label = { Text(stringResource(R.string.selfhost_domain)) },
                singleLine = true, enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = fingerprint, onValueChange = { fingerprint = it },
                label = { Text(stringResource(R.string.selfhost_fingerprint)) },
                placeholder = { Text("SHA256:...") },
                singleLine = true, enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.artemis_bridge_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.artemis_bridge_note),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = adbPort,
                onValueChange = {
                    if (it.length <= 5 && it.all(Char::isDigit)) {
                        adbPort = it
                        onArtemisPortsChanged(it.toIntOrNull() ?: 0, pairingPort.toIntOrNull() ?: 0)
                    }
                },
                label = { Text(stringResource(R.string.artemis_bridge_adb_port)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pairingPort,
                onValueChange = {
                    if (it.length <= 5 && it.all(Char::isDigit)) {
                        pairingPort = it
                        onArtemisPortsChanged(adbPort.toIntOrNull() ?: 0, it.toIntOrNull() ?: 0)
                    }
                },
                label = { Text(stringResource(R.string.artemis_bridge_pairing_port)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    busy = true
                    result = ""
                    scope.launch {
                        try {
                            val installed = OwnVpsInstaller(context.applicationContext).auditAndDeploy(
                                OwnVpsInstaller.Credentials(
                                    host = host, sshPort = sshPort.toIntOrNull() ?: 0,
                                    username = username, password = password,
                                    sshFingerprint = fingerprint, domain = domain,
                                ),
                                previousSecret = config.selfHostedTunnelToken,
                            )
                            onConfigured(installed.baseUrl, installed.tunnelSecret)
                            result = installed.report
                        } catch (e: Exception) {
                            result = "VPS: " + (e.message ?: "Connection failed")
                        } finally {
                            password = ""
                            busy = false
                        }
                    }
                },
                enabled = !busy && host.isNotBlank() && password.isNotEmpty() &&
                    domain.isNotBlank() && fingerprint.isNotBlank(),
            ) {
                if (busy) CircularProgressIndicator()
                else Text(stringResource(R.string.selfhost_install))
            }
            if (result.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(result, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
