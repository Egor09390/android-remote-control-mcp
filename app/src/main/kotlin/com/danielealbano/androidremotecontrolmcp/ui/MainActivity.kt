package com.danielealbano.androidremotecontrolmcp.ui

import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.personal.PersonalDashboard
import com.danielealbano.androidremotecontrolmcp.personal.PersonalSetup
import javax.inject.Inject
import android.Manifest
import android.net.ConnectivityManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.danielealbano.androidremotecontrolmcp.services.update.UpdateCheckScheduler
import com.danielealbano.androidremotecontrolmcp.ui.screens.MainScreen
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme
import com.danielealbano.androidremotecontrolmcp.ui.viewmodels.MainViewModel
import com.danielealbano.androidremotecontrolmcp.utils.RecentsUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var personalSetup: PersonalSetup
    private val viewModel: MainViewModel by viewModels()
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var cameraPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var microphonePermissionLauncher: ActivityResultLauncher<String>
    private lateinit var locationPermissionLauncher: ActivityResultLauncher<String>

    // The device's addresses also change while the app is visible (a VPN toggled from the quick settings, a new
    // Wi-Fi lease): the listener check follows every network while the activity is started. On the main thread,
    // where the view model is used
    private lateinit var networkChanges: NetworkChangeWatcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (BuildConfig.PERSONAL_BUILD) {
            setContent { PersonalDashboard() }
            return
        }
        networkChanges =
            NetworkChangeWatcher(getSystemService(ConnectivityManager::class.java), Handler(Looper.getMainLooper())) {
                viewModel.refreshListenerCheck()
            }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.hideFromRecents.collect { hide ->
                    updateExcludeFromRecents(hide)
                }
            }
        }

        notificationPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { _ ->
                viewModel.refreshPermissionStatus(this)
            }

        cameraPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { _ ->
                viewModel.refreshPermissionStatus(this)
            }

        microphonePermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { _ ->
                viewModel.refreshPermissionStatus(this)
            }

        locationPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { _ ->
                viewModel.refreshPermissionStatus(this)
            }

        setContent {
            AndroidRemoteControlMcpTheme {
                MainScreen(
                    onRequestNotificationPermission = ::requestNotificationPermission,
                    onRequestCameraPermission = ::requestCameraPermission,
                    onRequestMicrophonePermission = ::requestMicrophonePermission,
                    onRequestLocationPermission = ::requestLocationPermission,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Opportunistic update check each time the app is brought to the foreground (throttled by
        // WorkManager's unique-work policy). Complements the periodic background check.
        if (BuildConfig.PERSONAL_BUILD) return
        UpdateCheckScheduler.checkOnOpen(this)
        networkChanges.start()
    }

    override fun onStop() {
        if (BuildConfig.PERSONAL_BUILD) {
            personalSetup.scheduleBackup()
        } else networkChanges.stop()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (BuildConfig.PERSONAL_BUILD) {
            personalSetup.refresh()
            return
        }
        viewModel.refreshPermissionStatus(this)
        // The device's addresses may have changed meanwhile (the Specific IP must be one of them)
        viewModel.refreshListenerCheck()
    }
    // NOTE: No onPause() needed for broadcast receiver since we use StateFlow
    // for server status, which is collected in MainViewModel via viewModelScope.

    /**
     * Requests the POST_NOTIFICATIONS runtime permission.
     */
    private fun requestNotificationPermission() {
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Requests the CAMERA runtime permission.
     */
    private fun requestCameraPermission() {
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    /**
     * Requests the RECORD_AUDIO runtime permission.
     */
    private fun requestMicrophonePermission() {
        microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun updateExcludeFromRecents(hide: Boolean) {
        RecentsUtils.setExcludeFromRecents(this, hide)
    }
}
