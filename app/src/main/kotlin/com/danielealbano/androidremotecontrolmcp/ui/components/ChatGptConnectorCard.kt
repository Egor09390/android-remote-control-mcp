package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.danielealbano.androidremotecontrolmcp.R
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus

/**
 * Explicit ChatGPT connector URL. Only a connected, valid PUBLIC HTTPS tunnel address
 * can be copied: never offer http://127.0.0.1 or the bearer token as a connector URL.
 * The remote MCP endpoint still enforces the app's OAuth / bearer authentication.
 */
@Composable
fun ChatGptConnectorCard(
    serverStatus: ServerStatus,
    tunnelStatus: TunnelStatus,
    onCopyUrl: (String) -> Unit,
) {
    val publicEndpoint =
        if (serverStatus is ServerStatus.Running) {
            (tunnelStatus as? TunnelStatus.Connected)?.endpoints
                ?.firstOrNull { it.valid && it.url.startsWith("https://") }
                ?.url?.trimEnd('/')?.plus("/mcp")
        } else {
            null
        }

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.chatgpt_connector_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(8.dp))
            if (publicEndpoint != null) {
                Text(
                    text = publicEndpoint,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onCopyUrl(publicEndpoint) }) {
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null)
                    Text(
                        text = stringResource(R.string.chatgpt_connector_copy_url),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.chatgpt_connector_security_note),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    text = stringResource(R.string.chatgpt_connector_not_ready),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}
