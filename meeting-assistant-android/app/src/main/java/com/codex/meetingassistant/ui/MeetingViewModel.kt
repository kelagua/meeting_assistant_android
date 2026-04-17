package com.codex.meetingassistant.ui

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.codex.meetingassistant.AppContainer
import com.codex.meetingassistant.data.model.DeviceProfile
import com.codex.meetingassistant.data.model.ExportFormat
import com.codex.meetingassistant.data.model.MeetingDetail
import com.codex.meetingassistant.data.model.ModelPack
import com.codex.meetingassistant.data.model.SpeakerProfile
import com.codex.meetingassistant.service.MeetingCaptureService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppTab(val label: String) {
    Setup("准备"),
    Speakers("声纹"),
    Live("会议"),
    Review("总结"),
}

data class MeetingAssistantUiState(
    val selectedTab: AppTab = AppTab.Setup,
    val latestMeeting: MeetingDetail? = null,
    val speakerProfiles: List<SpeakerProfile> = emptyList(),
    val modelPacks: List<ModelPack> = emptyList(),
    val deviceProfile: DeviceProfile? = null,
    val recognizerLabel: String = "idle",
    val liveSummariesEnabled: Boolean = true,
    val speakerIdentificationEnabled: Boolean = true,
    val lastExportPath: String? = null,
    val statusMessage: String? = null,
)

private data class UiStateInputs(
    val selectedTab: AppTab,
    val latestMeeting: MeetingDetail?,
    val speakerProfiles: List<SpeakerProfile>,
    val modelPacks: List<ModelPack>,
    val recognizerLabel: String,
    val liveSummariesEnabled: Boolean,
    val speakerIdentificationEnabled: Boolean,
    val lastExportPath: String?,
)

class MeetingViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val selectedTab = MutableStateFlow(AppTab.Setup)
    private val statusMessage = MutableStateFlow<String?>(null)
    private val deviceProfile = MutableStateFlow(container.benchmarkSelector.profile())

    val uiState: StateFlow<MeetingAssistantUiState> = combine(
        selectedTab,
        container.meetingRepository.latestMeetingDetail,
        container.meetingRepository.speakerProfiles,
        container.meetingRepository.modelPacks,
        container.sessionCoordinator.runtimeState,
    ) { tab, meeting, speakers, modelPacks, runtimeState ->
        UiStateInputs(
            selectedTab = tab,
            latestMeeting = meeting,
            speakerProfiles = speakers,
            modelPacks = modelPacks,
            recognizerLabel = runtimeState.recognizerLabel,
            liveSummariesEnabled = runtimeState.liveSummariesEnabled,
            speakerIdentificationEnabled = runtimeState.speakerIdentificationEnabled,
            lastExportPath = runtimeState.lastExportPath,
        )
    }.combine(statusMessage) { inputs, message ->
        MeetingAssistantUiState(
            selectedTab = inputs.selectedTab,
            latestMeeting = inputs.latestMeeting,
            speakerProfiles = inputs.speakerProfiles,
            modelPacks = inputs.modelPacks,
            deviceProfile = deviceProfile.value,
            recognizerLabel = inputs.recognizerLabel,
            liveSummariesEnabled = inputs.liveSummariesEnabled,
            speakerIdentificationEnabled = inputs.speakerIdentificationEnabled,
            lastExportPath = inputs.lastExportPath,
            statusMessage = message,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MeetingAssistantUiState(),
    )

    init {
        viewModelScope.launch {
            container.modelPackManager.ensureSeededPacks()
        }
    }

    fun selectTab(tab: AppTab) {
        selectedTab.value = tab
    }

    fun createSpeakerProfile(displayName: String, keepsAudioSamples: Boolean) {
        if (displayName.isBlank()) return
        viewModelScope.launch {
            container.meetingRepository.upsertSpeakerProfile(displayName, keepsAudioSamples)
            statusMessage.value = "已录入 $displayName 的本地声纹档案"
        }
    }

    fun startMeeting(context: Context, title: String, agenda: String) {
        viewModelScope.launch {
            val meeting = container.meetingRepository.createMeeting(title, agenda)
            ContextCompat.startForegroundService(context, MeetingCaptureService.startIntent(context, meeting))
            selectedTab.value = AppTab.Live
            statusMessage.value = "会议已开始，本地录音和转写已启动"
        }
    }

    fun stopMeeting(context: Context) {
        context.startService(MeetingCaptureService.stopIntent(context))
        viewModelScope.launch {
            selectedTab.value = AppTab.Review
            statusMessage.value = "会议已停止，正在生成会后纪要"
        }
    }

    fun toggleSummariesPaused() {
        val meetingId = uiState.value.latestMeeting?.meeting?.id ?: return
        viewModelScope.launch {
            val current = uiState.value.latestMeeting?.meeting?.summariesPaused ?: false
            container.meetingRepository.setSummariesPaused(meetingId, !current)
            statusMessage.value = if (current) "已恢复会中提纲" else "已暂停会中提纲"
        }
    }

    fun renameSpeaker(oldLabel: String, newLabel: String) {
        val meetingId = uiState.value.latestMeeting?.meeting?.id ?: return
        if (oldLabel.isBlank() || newLabel.isBlank()) return
        viewModelScope.launch {
            container.meetingRepository.renameSpeakerLabel(meetingId, oldLabel, newLabel)
            statusMessage.value = "已将 $oldLabel 重命名为 $newLabel"
        }
    }

    fun lockSpeakerIdentity(speakerLabel: String, profileId: String) {
        val meetingId = uiState.value.latestMeeting?.meeting?.id ?: return
        viewModelScope.launch {
            container.meetingRepository.upsertSpeakerAssignment(
                meetingId = meetingId,
                speakerLabel = speakerLabel,
                profileId = profileId,
                confidence = 0.99f,
                lockedByUser = true,
            )
            statusMessage.value = "已锁定 $speakerLabel 的身份映射"
        }
    }

    fun exportLatestMeeting(format: ExportFormat) {
        viewModelScope.launch {
            container.sessionCoordinator.exportLatestMeeting(format)
            statusMessage.value = "已导出 ${format.name.lowercase()} 文件"
        }
    }
}

class MeetingViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return MeetingViewModel(container) as T
    }
}
