package com.danielealbano.androidremotecontrolmcp.personal

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.FileObserver
import android.os.PowerManager
import android.os.Process
import com.danielealbano.androidremotecontrolmcp.data.model.BindingAddress
import com.danielealbano.androidremotecontrolmcp.data.model.BuiltinStorageLocation
import com.danielealbano.androidremotecontrolmcp.data.model.ToolPermissionsConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.repository.SettingsRepository
import com.danielealbano.androidremotecontrolmcp.services.accessibility.McpAccessibilityService
import com.danielealbano.androidremotecontrolmcp.services.notifications.McpNotificationListenerService
import com.danielealbano.androidremotecontrolmcp.utils.PermissionUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class SetupItem(val id: String, val title: String, val granted: Boolean, val detail: String = "")
data class SetupState(
    val busy: Boolean = true,
    val initialized: Boolean = false,
    val items: List<SetupItem> = emptyList(),
    val restored: Boolean = false,
    val backupReady: Boolean = false,
    val message: String? = null,
)

/** Restores app-only state BEFORE any settings/OAuth DataStore is read. No permission dialogs are launched. */
@Singleton
class PersonalSetup @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val shell: RootShell,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val backupMutex = Mutex()
    private val _state = MutableStateFlow(SetupState())
    val state = _state.asStateFlow()
    private var rootAvailable = false
    private var backupJob: Job? = null
    private val observers = mutableListOf<FileObserver>()
    private val user = Process.myUid() / 100_000
    private val pkg get() = context.packageName
    private val data get() = context.applicationInfo.dataDir

    fun initialize() { scope.launch { configure() } }

    suspend fun awaitReady() { state.first { it.initialized && !it.busy } }

    private suspend fun configure() = mutex.withLock {
        _state.value = _state.value.copy(busy = true, message = null)
        try {
            if (!_state.value.initialized) {
                val restore = command(backupScript(restore = true))
                rootAvailable = restore?.output?.lineSequence()?.any { it == "ROOT_OK" } == true
                if (!rootAvailable) {
                    refreshItems()
                    _state.value = _state.value.copy(busy = false, message = "Разреши su в менеджере root и нажми «Повторить».")
                    return@withLock
                }
                if (restore?.code != 0) error("Restore failed")
                _state.value = _state.value.copy(restored = restore?.output?.contains("RESTORED_OK") == true)
                applyProfile()
            }
            val runtimePermissions = permissionNames().filter { name ->
                val info = runCatching { context.packageManager.getPermissionInfo(name, 0) }.getOrNull()
                info != null && info.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE == PermissionInfo.PROTECTION_DANGEROUS
            }
            val result = command(PersonalSetupScripts.grants(pkg, user, runtimePermissions))
            rootAvailable = result?.output?.lineSequence()?.any { it == "ROOT_OK" } == true
            // Settings.Secure changes are asynchronous; confirm the bound service, not just shell exit codes.
            repeat(12) {
                if (McpAccessibilityService.instance != null) return@repeat
                delay(250)
            }
            refreshItems()
            if (rootAvailable) {
                backupNow()
                observeStateFiles()
            }
            _state.value = _state.value.copy(
                busy = false,
                initialized = true,
                message = if (rootAvailable) null else "Нет root-доступа. Разреши su в менеджере root и повтори.",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            refreshItems()
            _state.value = _state.value.copy(busy = false, message = "Автонастройка завершилась с ошибкой. Нажми «Повторить».")
        }
    }

    /** Fixed personal profile once per installation. Existing account/token/client identities are preserved. */
    private suspend fun applyProfile() {
        val marker = File(context.noBackupFilesDir, "personal-profile-v1")
        settings.ensureAuthModelMigrated()
        if (marker.exists()) return
        settings.updateBindingAddress(BindingAddress.LOCALHOST)
        settings.updatePublicUrlOverride("")
        settings.updateHttpsEnabled(false) // Only the loopback hop is HTTP; CloudPub supplies public HTTPS.
        settings.updateTunnelProvider(TunnelProviderType.CLOUDPUB)
        settings.updateTunnelEnabled(true)
        settings.updateOauthEnabled(true)
        settings.updateBearerTokenEnabled(true)
        settings.updateAutoStartOnBoot(true)
        settings.updateHideFromRecents(false)
        settings.updateToolCallIndicatorEnabled(true)
        settings.updateArtemisRootAdbEnabled(false)
        settings.updateArtemisAdbPort(0)
        settings.updateArtemisPairingPort(0)
        settings.updateToolPermissionsConfig(ToolPermissionsConfig())
        settings.updateEventChannelEnabled(false)
        settings.updatePrivacyModeEnabled(false)
        settings.updatePrivacyModeCardDismissed(true)
        settings.updateAutoUpdateCheckEnabled(false) // Do not replace a personal build with an upstream release.
        settings.setAvailableUpdate(null)
        settings.updateFileSizeLimit(100)
        settings.updateDownloadTimeout(120)
        settings.updateAllowHttpDownloads(false)
        settings.updateAllowUnverifiedHttpsCerts(false)
        settings.updateBlockLocalNetworkDownloads(true)
        BuiltinStorageLocation.entries.forEach { location ->
            settings.updateBuiltinLocationAllowWrite(location.locationId, true)
            settings.updateBuiltinLocationAllowDelete(location.locationId, true)
        }
        marker.parentFile?.mkdirs()
        marker.writeText("1")
    }

    fun refresh() {
        if (!_state.value.initialized || _state.value.busy) return
        scope.launch { refreshItems() }
    }

    private fun permissionNames(): List<String> = context.packageManager.getPackageInfo(
        pkg,
        PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()),
    ).requestedPermissions.orEmpty().distinct().sorted()

    private fun refreshItems() {
        val manager = context.packageManager
        val special = listOf(
            SetupItem("root", "Root", rootAvailable, "Первое разрешение su выдаётся в менеджере root."),
            SetupItem("accessibility", "Управление экраном", PermissionUtils.isAccessibilityServiceEnabled(context, McpAccessibilityService::class.java) && McpAccessibilityService.instance != null,
                "Служба специальных возможностей должна быть включена и подключена."),
            SetupItem("notification_listener", "Чтение уведомлений", PermissionUtils.isNotificationListenerEnabled(context, McpNotificationListenerService::class.java)),
            SetupItem("battery", "Работа в фоне", context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(pkg)),
        )
        val permissions = permissionNames().map { name ->
            val title = runCatching { manager.getPermissionInfo(name, 0).loadLabel(manager).toString() }
                .getOrDefault(name.substringAfterLast('.').lowercase().replace('_', ' '))
            SetupItem(name, permissionTitle(name) ?: title, manager.checkPermission(name, pkg) == PackageManager.PERMISSION_GRANTED, name)
        }
        _state.value = _state.value.copy(items = special + permissions)
    }

    fun scheduleBackup() {
        if (!rootAvailable || !_state.value.initialized) return
        _state.value = _state.value.copy(backupReady = false)
        backupJob?.cancel()
        backupJob = scope.launch { delay(1_000); backupNow() }
    }

    private suspend fun backupNow() = backupMutex.withLock {
        val result = command(backupScript(restore = false))
        _state.value = _state.value.copy(backupReady = result?.code == 0 && result.output.contains("BACKUP_OK"))
    }

    private fun observeStateFiles() {
        if (observers.isNotEmpty()) return
        listOf(File(context.filesDir, "datastore"), File(context.noBackupFilesDir, "cloudpub")).forEach { directory ->
            directory.mkdirs()
            observers += object : FileObserver(directory, FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path in setOf("settings.preferences_pb", "oauth_clients.preferences_pb", "client.toml")) scheduleBackup()
                }
            }.also { it.startWatching() }
        }
    }

    private fun backupScript(restore: Boolean) = PersonalSetupScripts.backup(data, pkg, user, restore, Process.myUid())
    private suspend fun command(script: String): RootShell.Result? = try {
        withContext(Dispatchers.IO) { shell.execute(script) }
    } catch (e: CancellationException) { throw e } catch (_: Exception) { null }

    companion object {
        internal fun permissionTitle(name: String): String? = when (name.substringAfterLast('.')) {
            "INTERNET" -> "Интернет"
            "ACCESS_NETWORK_STATE" -> "Состояние сети"
            "POST_NOTIFICATIONS" -> "Уведомления"
            "CAMERA" -> "Камера"
            "RECORD_AUDIO" -> "Микрофон"
            "ACCESS_FINE_LOCATION" -> "Точная геопозиция"
            "ACCESS_COARSE_LOCATION" -> "Примерная геопозиция"
            "READ_MEDIA_IMAGES" -> "Фотографии"
            "READ_MEDIA_VIDEO" -> "Видео"
            "READ_MEDIA_AUDIO" -> "Аудиофайлы"
            "READ_MEDIA_VISUAL_USER_SELECTED" -> "Выбранные медиа"
            "QUERY_ALL_PACKAGES" -> "Список приложений"
            "KILL_BACKGROUND_PROCESSES" -> "Остановка приложений"
            "ACCESS_WIFI_STATE" -> "Состояние Wi-Fi"
            "CHANGE_WIFI_STATE" -> "Управление Wi-Fi"
            "NEARBY_WIFI_DEVICES" -> "Устройства Wi-Fi"
            "RECEIVE_BOOT_COMPLETED" -> "Запуск после перезагрузки"
            "FOREGROUND_SERVICE" -> "Фоновая служба"
            "FOREGROUND_SERVICE_SPECIAL_USE" -> "Служба управления"
            "FOREGROUND_SERVICE_DATA_SYNC" -> "Синхронизация"
            "FOREGROUND_SERVICE_LOCATION" -> "Геослужба"
            "WAKE_LOCK" -> "Работа без сна"
            "SCHEDULE_EXACT_ALARM" -> "Точные события"
            "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION" -> "Защищённые события"
            else -> null
        }
    }
}
