package com.danielealbano.androidremotecontrolmcp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.danielealbano.androidremotecontrolmcp.mcp.tools.LocalArtemisTools

@Composable
fun LocalArtemisSettings(configured: Boolean, canSave: Boolean, onSave: (String) -> Unit) {
    var token by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.local_artemis_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.local_artemis_details), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = token,
            onValueChange = { token = it.trim() },
            label = { Text(stringResource(R.string.local_artemis_token)) },
            placeholder = { if (configured) Text(stringResource(R.string.local_artemis_saved)) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            enabled = canSave,
            modifier = Modifier.fillMaxWidth(),
        )
        Row {
            TextButton(
                onClick = {
                    onSave(token)
                    token = ""
                },
                enabled = canSave && LocalArtemisTools.isValidToken(token),
            ) {
                Text(stringResource(R.string.local_artemis_save))
            }
            if (configured) {
                TextButton(onClick = {
                    onSave("")
                    token = ""
                }) {
                    Text(stringResource(R.string.local_artemis_disable))
                }
            }
        }
    }
}
