@file:Suppress("FunctionNaming", "LongMethod", "MagicNumber")

package com.danielealbano.androidremotecontrolmcp.personal

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.danielealbano.androidremotecontrolmcp.data.model.ServerStatus
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.ui.ApprovalActivity
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PersonalSetupViewModel @Inject constructor(val setup: PersonalSetup) : ViewModel()

private val Accent = Color(0xFFB2A3FF)
private val Success = Color(0xFF6ED4AA)
private val Failure = Color(0xFFFF969C)
private val CardSurface = Color(0xFF1B1D28)

@Composable
fun PersonalDashboard(root: PersonalSetupViewModel = hiltViewModel()) {
    val state by root.setup.state.collectAsStateWithLifecycle()
    AndroidRemoteControlMcpTheme(darkTheme = true, dynamicColor = false) {
        Surface(color = Color(0xFF101119), modifier = Modifier.fillMaxSize()) {
            if (!state.initialized) {
                Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("S23 CONTROL", style = MaterialTheme.typography.headlineMedium, color = Accent)
                    if (state.busy) {
                        CircularProgressIndicator(color = Accent, modifier = Modifier.size(28.dp))
                        Text("Автонастройка", style = MaterialTheme.typography.titleMedium)
                    }
                    state.message?.let { Text(it, color = Failure, style = MaterialTheme.typography.bodyMedium) }
                    if (state.items.isNotEmpty()) PermissionChecklist(state, root.setup)
                }
            } else {
                PersonalControlPanel(state, root.setup)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonalControlPanel(state: SetupState, setup: PersonalSetup, model: MainViewModel = hiltViewModel()) {
    val config by model.serverConfig.collectAsStateWithLifecycle()
    val server by model.serverStatus.collectAsStateWithLifecycle()
    val tunnel by model.tunnelStatus.collectAsStateWithLifecycle()
    val approvals by model.pendingApprovalCount.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val screenReady = state.items.firstOrNull { it.id == "accessibility" }?.granted == true
    val running = server is ServerStatus.Running
    val busy = state.busy || server is ServerStatus.Starting || server is ServerStatus.Stopping
    val url = (tunnel as? TunnelStatus.Connected)?.endpoints?.firstOrNull { it.valid && it.url.startsWith("https://") }?.url?.trimEnd('/')?.plus("/mcp")

    LaunchedEffect(Unit) {
        while (true) { delay(3_000); setup.refresh() }
    }
    LaunchedEffect(screenReady, config.cloudpubToken, state.busy) {
        if (!autoStarted && screenReady && !state.busy && config.cloudpubToken.isNotBlank()) {
            autoStarted = true
            if (server is ServerStatus.Stopped) model.startServer(context)
        }
    }
    LaunchedEffect(copied) { if (copied) { delay(1_800); copied = false } }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("S23", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("CONTROL", color = Accent, style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Настройки", tint = Accent) }
        }
        Surface(shape = RoundedCornerShape(26.dp), color = CardSurface) {
            Column(
                Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(Color(0xFF29243D), CardSurface))).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).background(if (url != null) Success else Accent, RoundedCornerShape(8.dp)))
                    Text(
                        when {
                            url != null -> "Подключено"
                            tunnel is TunnelStatus.Connecting -> "Соединяюсь с CloudPub"
                            running -> "MCP работает"
                            busy -> "Подготовка"
                            else -> "Готов к работе"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("CloudPub", color = Accent, style = MaterialTheme.typography.labelSmall)
                }
                if (url != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer(Modifier.weight(1f)) { Text(url, style = MaterialTheme.typography.bodySmall, color = Color(0xFFCCC8DF)) }
                        IconButton(onClick = {
                            scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("MCP", url))); copied = true }
                        }) { Icon(Icons.Default.ContentCopy, if (copied) "Скопировано" else "Копировать ссылку", tint = Accent) }
                    }
                    if (copied) Text("Скопировано", color = Success, style = MaterialTheme.typography.labelSmall)
                }
                Button(
                    onClick = {
                        if (running) { autoStarted = true; model.stopServer(context) }
                        else if (config.cloudpubToken.isBlank()) showSettings = true
                        else { autoStarted = true; model.startServer(context) }
                    },
                    enabled = !busy && (running || screenReady),
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF211A39)),
                ) {
                    Icon(if (running) Icons.Default.Stop else Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (running) "Остановить" else if (config.cloudpubToken.isBlank()) "Подключить CloudPub" else "Включить", fontWeight = FontWeight.SemiBold)
                }
                val error = (server as? ServerStatus.Error)?.message ?: (tunnel as? TunnelStatus.Error)?.message ?: state.message
                if (error != null) Text(error, color = Failure, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (approvals > 0) {
            FilledTonalButton(onClick = { context.startActivity(Intent(context, ApprovalActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Подтвердить подключение · $approvals")
            }
        }
        PermissionChecklist(state, setup)
    }
    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }, containerColor = CardSurface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Настройки", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(token, { token = it.trim() }, label = { Text("Токен CloudPub") },
                    placeholder = { Text(if (config.cloudpubToken.isNotBlank()) "Сохранён" else "Из личного кабинета CloudPub") },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !running && !busy)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Запуск после перезагрузки", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(config.autoStartOnBoot, model::updateAutoStartOnBoot)
                }
                Text(if (state.backupReady) "✓ Настройки сохранены для переустановки" else "✕ Резервная копия ещё не создана",
                    color = if (state.backupReady) Success else Failure, style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    model.activateCloudpub(context, token)
                    token = ""
                    autoStarted = true
                    showSettings = false
                }, enabled = !running && !busy && (token.isNotBlank() || config.cloudpubToken.isNotBlank()), modifier = Modifier.fillMaxWidth()) { Text("Сохранить и подключить") }
            }
        }
    }
}

@Composable
private fun PermissionChecklist(state: SetupState, setup: PersonalSetup) {
    var expanded by remember { mutableStateOf<String?>(null) }
        Surface(shape = RoundedCornerShape(24.dp), color = CardSurface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Доступы", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text("${state.items.count { it.granted }} / ${state.items.size}", color = Accent, style = MaterialTheme.typography.labelMedium)
                    }
                    if (state.busy) CircularProgressIndicator(Modifier.size(22.dp), color = Accent, strokeWidth = 2.dp)
                    else IconButton(onClick = { setup.initialize() }) { Icon(Icons.Default.Refresh, "Повторить автонастройку", tint = Accent) }
                }
                state.items.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { item ->
                            Row(
                                Modifier.weight(1f).clickable { expanded = if (expanded == item.id) null else item.id }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Icon(if (item.granted) Icons.Default.CheckCircle else Icons.Default.Close,
                                    if (item.granted) "Разрешено" else "Не выдано", tint = if (item.granted) Success else Failure, modifier = Modifier.size(18.dp))
                                Text(item.title, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                    pair.firstOrNull { it.id == expanded }?.let { item ->
                        Text(if (item.granted) item.detail.ifEmpty { "Доступ подтверждён" } else "Не подтверждено системой. ${item.detail}",
                            style = MaterialTheme.typography.bodySmall, color = if (item.granted) Accent else Failure)
                    }
                }
            }
        }
}
