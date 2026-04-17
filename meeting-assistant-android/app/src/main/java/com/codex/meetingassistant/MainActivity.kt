package com.codex.meetingassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codex.meetingassistant.ui.MeetingAssistantRoot
import com.codex.meetingassistant.ui.MeetingViewModel
import com.codex.meetingassistant.ui.MeetingViewModelFactory
import com.codex.meetingassistant.ui.theme.MeetingAssistantTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MeetingAssistantTheme {
                val app = application as MeetingAssistantApp
                val viewModel: MeetingViewModel = viewModel(
                    factory = MeetingViewModelFactory(app.container),
                )
                MeetingAssistantRoot(viewModel = viewModel)
            }
        }
    }
}
