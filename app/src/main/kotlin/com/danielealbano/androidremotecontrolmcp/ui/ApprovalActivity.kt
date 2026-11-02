package com.danielealbano.androidremotecontrolmcp.ui

import com.danielealbano.androidremotecontrolmcp.BuildConfig
import com.danielealbano.androidremotecontrolmcp.personal.PersonalSetup
import javax.inject.Inject
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.danielealbano.androidremotecontrolmcp.ui.screens.ApprovalScreen
import com.danielealbano.androidremotecontrolmcp.ui.theme.AndroidRemoteControlMcpTheme
import dagger.hilt.android.AndroidEntryPoint

/** Hosts the on-device OAuth approval screen; launched from the approval notification. */
@AndroidEntryPoint
class ApprovalActivity : ComponentActivity() {
    @Inject lateinit var personalSetup: PersonalSetup

    override fun onStop() {
        super.onStop()
        if (BuildConfig.PERSONAL_BUILD) personalSetup.scheduleBackup()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AndroidRemoteControlMcpTheme {
                ApprovalScreen(onAllHandled = ::finish)
            }
        }
    }
}
