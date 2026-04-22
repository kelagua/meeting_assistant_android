package com.codex.meetingassistant.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.codex.meetingassistant.AppContainer
import com.codex.meetingassistant.R
import com.codex.meetingassistant.data.model.DeviceProfile
import com.codex.meetingassistant.data.model.ExportFormat
import com.codex.meetingassistant.data.model.GalleryModel
import com.codex.meetingassistant.data.model.MeetingDetail
import com.codex.meetingassistant.data.model.MeetingPreferences
import com.codex.meetingassistant.data.model.ModelPack
import com.codex.meetingassistant.data.model.ModelPackStatus
import com.codex.meetingassistant.data.model.SpeakerProfile
import com.codex.meetingassistant.data.model.SummaryStage
import com.codex.meetingassistant.runtime.benchmark.BenchmarkSelector
import com.codex.meetingassistant.runtime.llm.ModelRuntimeState
import com.codex.meetingassistant.runtime.sherpa.SherpaOnnxSupport
import com.codex.meetingassistant.runtime.speaker.EnrollmentMode
import com.codex.meetingassistant.service.MeetingCaptureService
import com.codex.meetingassistant.service.ModelDownloadService
import com.codex.meetingassistant.service.SessionRuntimeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Message type + optional argument for localization. */
data class StatusMessage(
    val stringResId: Int,
    val arg: String? = null,
)

enum class AppTab(val labelResId: Int) {
    Setup(R.string.tab_home),
    Speakers(R.string.tab_people),
    Live(R.string.tab_live),
    Review(R.string.tab_notes),
    Models(R.string.tab_models),
}

data class PermissionUiState(
    val microphoneGranted: Boolean = false,
    val notificationGranted: Boolean = true,
    val missingPermissions: List<String> = emptyList(),
) {
    val canStartMeeting: Boolean
        get() = microphoneGranted
}

/** Runtime diagnostics use Int resource IDs for localization. */
data class RuntimeDiagnosticsUiState(
    val sherpaStatusResId: Int = R.string.diag_checking_sherpa,
    val speakerRuntimeStatusResId: Int = R.string.diag_checking_speaker,
    val llmStatusResId: Int = R.string.diag_checking_llm,
    val livePackStatusResId: Int = R.string.diag_no_live_pack,
    val finalPackStatusResId: Int = R.string.diag_no_final_pack,
    val lastError: String? = null,
)

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
    val statusMessage: StatusMessage? = null,
    val permissions: PermissionUiState = PermissionUiState(),
    val runtimeDiagnostics: RuntimeDiagnosticsUiState = RuntimeDiagnosticsUiState(),
    val modelMarketUiState: ModelMarketUiState = ModelMarketUiState(),
)

private data class UiStateInputs(
    val selectedTab: AppTab,
    val latestMeeting: MeetingDetail?,
    val speakerProfiles: List<SpeakerProfile>,
    val modelPacks: List<ModelPack>,
    val deviceProfile: DeviceProfile,
    val permissions: PermissionUiState,
    val preferences: MeetingPreferences,
    val sessionRuntimeState: SessionRuntimeState,
    val modelRuntimeState: ModelRuntimeState,
)

class MeetingViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val selectedTab = MutableStateFlow(AppTab.Setup)
    private val statusMessage = MutableStateFlow<StatusMessage?>(null)
    private val deviceProfile = MutableStateFlow(container.benchmarkSelector.profile())
    private val modelMarketUiState = MutableStateFlow(ModelMarketUiState())
    private val permissionState = MutableStateFlow(readPermissionState())

    val uiState: StateFlow<MeetingAssistantUiState> = selectedTab
        .combine(container.meetingRepository.latestMeetingDetail) { tab, meeting ->
            UiStateInputs(
                selectedTab = tab,
                latestMeeting = meeting,
                speakerProfiles = emptyList(),
                modelPacks = emptyList(),
                deviceProfile = deviceProfile.value,
                permissions = permissionState.value,
                preferences = MeetingPreferences(),
                sessionRuntimeState = SessionRuntimeState(),
                modelRuntimeState = ModelRuntimeState(),
            )
        }
        .combine(container.meetingRepository.speakerProfiles) { inputs, speakers ->
            inputs.copy(speakerProfiles = speakers)
        }
        .combine(container.meetingRepository.modelPacks) { inputs, modelPacks ->
            inputs.copy(modelPacks = modelPacks)
        }
        .combine(deviceProfile) { inputs, profile ->
            inputs.copy(deviceProfile = profile)
        }
        .combine(permissionState) { inputs, permissions ->
            inputs.copy(permissions = permissions)
        }
        .combine(container.settingsRepository.preferences) { inputs, preferences ->
            inputs.copy(preferences = preferences)
        }
        .combine(container.sessionCoordinator.runtimeState) { inputs, runtimeState ->
            inputs.copy(sessionRuntimeState = runtimeState)
        }
        .combine(container.modelRuntime.runtimeState) { inputs, runtimeState ->
            inputs.copy(modelRuntimeState = runtimeState)
        }
        .combine(statusMessage) { inputs, message ->
            val diagnostics = buildRuntimeDiagnostics(inputs)
            MeetingAssistantUiState(
                selectedTab = inputs.selectedTab,
                latestMeeting = inputs.latestMeeting,
                speakerProfiles = inputs.speakerProfiles,
                modelPacks = inputs.modelPacks,
                deviceProfile = inputs.deviceProfile,
                recognizerLabel = inputs.sessionRuntimeState.recognizerLabel,
                liveSummariesEnabled = inputs.sessionRuntimeState.liveSummariesEnabled,
                speakerIdentificationEnabled = inputs.sessionRuntimeState.speakerIdentificationEnabled,
                lastExportPath = inputs.sessionRuntimeState.lastExportPath,
                statusMessage = message,
                permissions = inputs.permissions,
                runtimeDiagnostics = diagnostics,
                modelMarketUiState = modelMarketUiState.value,
            )
        }
        .combine(modelMarketUiState) { inputs, marketState ->
            inputs.copy(modelMarketUiState = marketState)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MeetingAssistantUiState(),
        )

    init {
        refreshPermissionState()
        viewModelScope.launch {
            container.modelPackManager.ensureSeededPacks()
            val galleryModels = container.modelRepository.getGalleryModels()
            val profile = container.benchmarkSelector.profile()
            deviceProfile.value = profile
            modelMarketUiState.value = modelMarketUiState.value.copy(
                galleryModels = galleryModels,
                deviceMemoryGb = profile.ramGb,
            )
        }
        viewModelScope.launch {
            container.modelRepository.downloadTasks.collect { tasks ->
                modelMarketUiState.value = modelMarketUiState.value.copy(
                    downloadTasks = tasks.associateBy { it.modelPackId },
                )
            }
        }
    }

    fun selectTab(tab: AppTab) {
        selectedTab.value = tab
    }

    fun refreshPermissionState() {
        permissionState.value = readPermissionState()
    }

    fun createSpeakerProfile(displayName: String, keepsAudioSamples: Boolean) {
        if (displayName.isBlank()) return
        refreshPermissionState()
        if (!permissionState.value.microphoneGranted) {
            statusMessage.value = StatusMessage(R.string.msg_mic_required_enrollment)
            selectedTab.value = AppTab.Setup
            return
        }

        viewModelScope.launch {
            statusMessage.value = StatusMessage(R.string.msg_recording_enrollment, displayName)
            runCatching {
                val enrollment = container.speakerEnrollmentRecorder.recordAndExtractEmbedding(
                    displayName = displayName,
                    keepsAudioSamples = keepsAudioSamples,
                )
                container.meetingRepository.upsertSpeakerProfile(
                    displayName = displayName,
                    keepsAudioSamples = keepsAudioSamples,
                    embeddingBase64 = enrollment.embeddingBase64,
                    enrollmentCount = 1,
                )
                enrollment
            }.onSuccess { enrollment ->
                statusMessage.value = when (enrollment.mode) {
                    EnrollmentMode.SHERPA -> StatusMessage(R.string.msg_sherpa_enrollment_done, displayName)
                    EnrollmentMode.FALLBACK -> StatusMessage(R.string.msg_fallback_enrollment_done, displayName)
                }
            }.onFailure {
                statusMessage.value = StatusMessage(R.string.msg_enrollment_failed)
            }
        }
    }

    fun startMeeting(context: Context, title: String, agenda: String) {
        refreshPermissionState()
        if (!permissionState.value.microphoneGranted) {
            selectedTab.value = AppTab.Setup
            statusMessage.value = StatusMessage(R.string.msg_mic_required_meeting)
            return
        }

        viewModelScope.launch {
            runCatching {
                val meeting = container.meetingRepository.createMeeting(title, agenda)
                ContextCompat.startForegroundService(context, MeetingCaptureService.startIntent(context, meeting))
            }.onSuccess {
                selectedTab.value = AppTab.Live
                statusMessage.value = StatusMessage(R.string.msg_starting_capture)
            }.onFailure { error ->
                selectedTab.value = AppTab.Setup
                statusMessage.value = error.message?.let {
                    StatusMessage(R.string.msg_service_start_failed, it)
                } ?: StatusMessage(R.string.msg_service_start_failed)
            }
        }
    }

    fun stopMeeting(context: Context) {
        context.startService(MeetingCaptureService.stopIntent(context))
        viewModelScope.launch {
            selectedTab.value = AppTab.Review
            statusMessage.value = StatusMessage(R.string.msg_generating_final)
        }
    }

    fun toggleSummariesPaused() {
        val meetingId = uiState.value.latestMeeting?.meeting?.id ?: return
        viewModelScope.launch {
            val current = uiState.value.latestMeeting?.meeting?.summariesPaused ?: false
            container.meetingRepository.setSummariesPaused(meetingId, !current)
            statusMessage.value = if (current) {
                StatusMessage(R.string.msg_notes_resumed)
            } else {
                StatusMessage(R.string.msg_notes_paused)
            }
        }
    }

    fun renameSpeaker(oldLabel: String, newLabel: String) {
        val meetingId = uiState.value.latestMeeting?.meeting?.id ?: return
        if (oldLabel.isBlank() || newLabel.isBlank()) return
        viewModelScope.launch {
            container.meetingRepository.renameSpeakerLabel(meetingId, oldLabel, newLabel)
            statusMessage.value = StatusMessage(R.string.msg_speaker_renamed, "$oldLabel → $newLabel")
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
            statusMessage.value = StatusMessage(R.string.msg_identity_locked, speakerLabel)
        }
    }

    fun exportLatestMeeting(format: ExportFormat) {
        viewModelScope.launch {
            container.sessionCoordinator.exportLatestMeeting(format)
            statusMessage.value = StatusMessage(R.string.msg_exported, format.name)
        }
    }

    fun downloadModel(context: Context, model: GalleryModel) {
        viewModelScope.launch {
            container.modelRepository.seedGalleryModels(listOf(model))
            ContextCompat.startForegroundService(
                context,
                ModelDownloadService.startIntent(
                    context,
                    model.id,
                    model.downloadUrl,
                    model.sizeInBytes,
                ),
            )
            statusMessage.value = StatusMessage(R.string.msg_starting_download, model.displayName)
        }
    }

    fun cancelDownload(modelPackId: String) {
        viewModelScope.launch {
            container.modelDownloadManager.cancelDownload(modelPackId)
            statusMessage.value = StatusMessage(R.string.msg_download_cancelled)
        }
    }

    fun deleteModel(modelPackId: String) {
        viewModelScope.launch {
            container.modelDownloadManager.deleteModel(modelPackId)
            statusMessage.value = StatusMessage(R.string.msg_model_deleted)
        }
    }

    private fun readPermissionState(): PermissionUiState {
        val context = container.applicationContext
        val microphoneGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val notificationGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        val missing = buildList {
            if (!microphoneGranted) add("Microphone")
            if (!notificationGranted) add("Notifications")
        }

        return PermissionUiState(
            microphoneGranted = microphoneGranted,
            notificationGranted = notificationGranted,
            missingPermissions = missing,
        )
    }

    private fun buildRuntimeDiagnostics(inputs: UiStateInputs): RuntimeDiagnosticsUiState {
        val livePack = choosePackForStage(
            profile = inputs.deviceProfile,
            modelPacks = inputs.modelPacks,
            stage = SummaryStage.LIVE_NOTES,
            preferredPackId = inputs.preferences.selectedLivePackId,
        )
        val finalPack = choosePackForStage(
            profile = inputs.deviceProfile,
            modelPacks = inputs.modelPacks,
            stage = SummaryStage.FINAL_SUMMARY,
            preferredPackId = inputs.preferences.selectedFinalPackId,
        )

        return RuntimeDiagnosticsUiState(
            sherpaStatusResId = buildSherpaStatusResId(inputs.sessionRuntimeState.recognizerLabel),
            speakerRuntimeStatusResId = buildSpeakerStatusResId(inputs.sessionRuntimeState.recognizerLabel),
            llmStatusResId = buildLlmStatusResId(livePack, finalPack, inputs.modelRuntimeState),
            livePackStatusResId = buildPackStatusResId("live summary", livePack),
            finalPackStatusResId = buildPackStatusResId("final summary", finalPack),
            lastError = inputs.sessionRuntimeState.lastError,
        )
    }

    private fun choosePackForStage(
        profile: DeviceProfile,
        modelPacks: List<ModelPack>,
        stage: SummaryStage,
        preferredPackId: String?,
    ): ModelPack? {
        val localPacks = modelPacks.filter { it.stage == stage && !it.localFilePath.isNullOrBlank() }
        return if (localPacks.isNotEmpty()) {
            BenchmarkSelector.choosePack(
                profile = profile,
                modelPacks = localPacks,
                stage = stage,
                preferredPackId = preferredPackId,
            ) ?: BenchmarkSelector.choosePack(
                profile = profile,
                modelPacks = modelPacks,
                stage = stage,
                preferredPackId = preferredPackId,
            )
        } else {
            chooseAnyLocalPack(
                profile = profile,
                modelPacks = modelPacks,
                preferredPackId = preferredPackId,
            ) ?: BenchmarkSelector.choosePack(
                profile = profile,
                modelPacks = modelPacks,
                stage = stage,
                preferredPackId = preferredPackId,
            )
        }
    }

    private fun chooseAnyLocalPack(
        profile: DeviceProfile,
        modelPacks: List<ModelPack>,
        preferredPackId: String?,
    ): ModelPack? {
        val localPacks = modelPacks.filter { !it.localFilePath.isNullOrBlank() }
        if (localPacks.isEmpty()) return null

        preferredPackId?.let { preferredId ->
            localPacks.firstOrNull { it.id == preferredId }?.let { return it }
        }

        return localPacks
            .filter { pack -> profile.ramGb >= pack.minRamGb && profile.score >= pack.minScore }
            .maxByOrNull { it.estimatedSizeMb }
            ?: localPacks.minByOrNull { it.estimatedSizeMb }
    }

    private fun buildSherpaStatusResId(recognizerLabel: String): Int {
        val missingAssets = SherpaOnnxSupport.missingAsrAssets(container.applicationContext.assets)
        val fallbackReason = when {
            !SherpaOnnxSupport.isNativeLibraryAvailable ->
                SherpaOnnxSupport.nativeLoadError?.message ?: "the sherpa-onnx native library is missing"
            missingAssets.isNotEmpty() ->
                "required ASR assets are missing: ${missingAssets.joinToString()}"
            recognizerLabel.startsWith("demo-scripted") ->
                recognizerLabel.removePrefix("demo-scripted").trim().trim('(', ')').ifBlank {
                    "sherpa-onnx could not initialize"
                }
            else -> null
        }

        return when {
            recognizerLabel.startsWith("sherpa-onnx") ->
                R.string.diag_sherpa_active
            fallbackReason != null ->
                R.string.diag_sherpa_demo_fallback
            else ->
                R.string.diag_sherpa_ready
        }
    }

    private fun buildSpeakerStatusResId(recognizerLabel: String): Int {
        val missingAssets = SherpaOnnxSupport.missingSpeakerAssets(container.applicationContext.assets)
        return when {
            recognizerLabel.contains("speaker") -> R.string.diag_speaker_active
            !SherpaOnnxSupport.isNativeLibraryAvailable -> R.string.diag_speaker_native_missing
            missingAssets.isNotEmpty() -> R.string.diag_speaker_model_missing
            else -> R.string.diag_speaker_ready
        }
    }

    private fun buildLlmStatusResId(
        livePack: ModelPack?,
        finalPack: ModelPack?,
        runtimeState: ModelRuntimeState,
    ): Int {
        val downloadedPack = listOf(livePack, finalPack).firstOrNull { !it?.localFilePath.isNullOrBlank() }
        return when {
            runtimeState.backendLabel.startsWith("litert") -> R.string.diag_litert_active
            runtimeState.lastError != null -> R.string.diag_litert_error
            downloadedPack != null -> R.string.diag_litert_ready
            else -> R.string.diag_litert_no_model
        }
    }

    private fun buildPackStatusResId(stageLabel: String, pack: ModelPack?): Int =
        when {
            pack == null -> R.string.diag_no_compatible_pack
            !pack.localFilePath.isNullOrBlank() -> R.string.diag_pack_downloaded
            pack.status == ModelPackStatus.DOWNLOADING -> R.string.diag_pack_downloading
            else -> R.string.diag_pack_no_file
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
