package com.codex.meetingassistant.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.ModelTraining
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.codex.meetingassistant.R
import com.codex.meetingassistant.data.model.ExportFormat
import com.codex.meetingassistant.data.model.MeetingStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingAssistantRoot(viewModel: MeetingViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    var draftTitle by rememberSaveable { mutableStateOf("Team Sync") }
    var draftAgenda by rememberSaveable { mutableStateOf("Status updates, blockers, and next steps") }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.refreshPermissionState()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshPermissionState()
    }

    // Resolve status message to display string
    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let { msg ->
            val text = if (msg.arg != null) {
                context.getString(msg.stringResId, msg.arg)
            } else {
                context.getString(msg.stringResId)
            }
            snackbarHostState.showSnackbar(text)
        }
    }

    LaunchedEffect(uiState.runtimeDiagnostics.lastError) {
        uiState.runtimeDiagnostics.lastError?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    AssistChip(
                        onClick = { viewModel.selectTab(AppTab.Setup) },
                        label = {
                            Text(
                                text = uiState.recognizerLabel,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                },
            )
        },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = uiState.selectedTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        alwaysShowLabel = false,
                        colors = NavigationBarItemDefaults.colors(),
                        icon = {
                            when (tab) {
                                AppTab.Setup -> Icon(Icons.Outlined.Dashboard, contentDescription = null)
                                AppTab.Speakers -> Icon(Icons.Outlined.Groups, contentDescription = null)
                                AppTab.Live -> Icon(Icons.Outlined.GraphicEq, contentDescription = null)
                                AppTab.Review -> Icon(Icons.Outlined.Description, contentDescription = null)
                                AppTab.Models -> Icon(Icons.Outlined.ModelTraining, contentDescription = null)
                            }
                        },
                        label = {
                            Text(
                                text = stringResource(tab.labelResId),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.surfaceContainerLow,
                        ),
                    ),
                )
                .padding(innerPadding),
        ) {
            when (uiState.selectedTab) {
                AppTab.Setup -> SetupScreen(
                    uiState = uiState,
                    draftTitle = draftTitle,
                    onDraftTitleChange = { draftTitle = it },
                    draftAgenda = draftAgenda,
                    onDraftAgendaChange = { draftAgenda = it },
                    requestPermissions = {
                        val permissions = buildList {
                            if (!uiState.permissions.microphoneGranted) {
                                add(Manifest.permission.RECORD_AUDIO)
                            }
                            if (
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                !uiState.permissions.notificationGranted
                            ) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        if (permissions.isEmpty()) {
                            viewModel.refreshPermissionState()
                        } else {
                            permissionLauncher.launch(permissions.toTypedArray())
                        }
                    },
                    openLiveTab = { viewModel.selectTab(AppTab.Live) },
                    onStartMeeting = { title, agenda -> viewModel.startMeeting(context, title, agenda) },
                )

                AppTab.Speakers -> SpeakerProfilesScreen(
                    uiState = uiState,
                    onCreateProfile = viewModel::createSpeakerProfile,
                )

                AppTab.Live -> LiveMeetingScreen(
                    uiState = uiState,
                    draftTitle = draftTitle,
                    onDraftTitleChange = { draftTitle = it },
                    draftAgenda = draftAgenda,
                    onDraftAgendaChange = { draftAgenda = it },
                    onStartMeeting = { title, agenda -> viewModel.startMeeting(context, title, agenda) },
                    onStopMeeting = { viewModel.stopMeeting(context) },
                    onToggleSummaries = viewModel::toggleSummariesPaused,
                    onRenameSpeaker = viewModel::renameSpeaker,
                    onLockSpeakerIdentity = viewModel::lockSpeakerIdentity,
                )

                AppTab.Review -> ReviewScreen(
                    uiState = uiState,
                    onExport = viewModel::exportLatestMeeting,
                )

                AppTab.Models -> ModelMarketScreen(
                    uiState = uiState.modelMarketUiState,
                    onDownload = { model -> viewModel.downloadModel(context, model) },
                    onCancel = viewModel::cancelDownload,
                    onDelete = viewModel::deleteModel,
                )
            }
        }
    }
}

@Composable
private fun SetupScreen(
    uiState: MeetingAssistantUiState,
    draftTitle: String,
    onDraftTitleChange: (String) -> Unit,
    draftAgenda: String,
    onDraftAgendaChange: (String) -> Unit,
    requestPermissions: () -> Unit,
    openLiveTab: () -> Unit,
    onStartMeeting: (String, String) -> Unit,
) {
    val activeMeeting = uiState.latestMeeting?.takeIf {
        it.meeting.status == MeetingStatus.LIVE || it.meeting.status == MeetingStatus.PROCESSING
    }

    val diag = uiState.runtimeDiagnostics

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeroCard(
            title = stringResource(R.string.setup_hero_title),
            body = stringResource(R.string.setup_hero_body),
        )

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.device_profile), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                StatusItem(stringResource(R.string.memory), "${uiState.deviceProfile?.ramGb ?: "--"} GB")
                StatusItem(stringResource(R.string.cpu_cores), "${uiState.deviceProfile?.cpuCores ?: "--"}")
                StatusItem(stringResource(R.string.abi), uiState.deviceProfile?.abi ?: "--")
                StatusItem(stringResource(R.string.device_score), "${uiState.deviceProfile?.score ?: "--"}")
            }
        }

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.permissions), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                StatusItem(
                    stringResource(R.string.microphone),
                    if (uiState.permissions.microphoneGranted) {
                        stringResource(R.string.permission_granted_recording)
                    } else {
                        stringResource(R.string.permission_missing_mic)
                    },
                )
                StatusItem(
                    stringResource(R.string.notifications),
                    if (uiState.permissions.notificationGranted) {
                        stringResource(R.string.permission_granted_notif)
                    } else {
                        stringResource(R.string.permission_missing_notif)
                    },
                )
                if (uiState.permissions.missingPermissions.isEmpty()) {
                    Text(stringResource(R.string.permissions_all_ready), color = MaterialTheme.colorScheme.primary)
                } else {
                    Text(
                        text = stringResource(R.string.permissions_missing, uiState.permissions.missingPermissions.joinToString()),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = requestPermissions, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.request_permissions))
                    }
                }
            }
        }

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.runtime_readiness), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                StatusItem(stringResource(R.string.asr), LocalContext.current.getString(diag.sherpaStatusResId))
                StatusItem(stringResource(R.string.speaker_id), LocalContext.current.getString(diag.speakerRuntimeStatusResId))
                StatusItem(stringResource(R.string.llm), LocalContext.current.getString(diag.llmStatusResId))
                StatusItem(stringResource(R.string.live_pack), LocalContext.current.getString(diag.livePackStatusResId))
                StatusItem(stringResource(R.string.final_pack), LocalContext.current.getString(diag.finalPackStatusResId))
                diag.lastError?.let { error ->
                    Text(
                        text = stringResource(R.string.last_error, error),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.quick_start), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (activeMeeting != null) {
                    Text(stringResource(R.string.meeting_running, activeMeeting.meeting.title))
                    FilledTonalButton(onClick = openLiveTab, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.open_live_console))
                    }
                } else {
                    OutlinedTextField(
                        value = draftTitle,
                        onValueChange = onDraftTitleChange,
                        label = { Text(stringResource(R.string.meeting_title)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draftAgenda,
                        onValueChange = onDraftAgendaChange,
                        label = { Text(stringResource(R.string.agenda_or_focus)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { onStartMeeting(draftTitle, draftAgenda) },
                        enabled = uiState.permissions.canStartMeeting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.start_meeting_now))
                    }
                    if (!uiState.permissions.canStartMeeting) {
                        Text(
                            text = stringResource(R.string.grant_mic_permission),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    FilledTonalButton(onClick = openLiveTab, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.open_live_tab))
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeakerProfilesScreen(
    uiState: MeetingAssistantUiState,
    onCreateProfile: (String, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var keepSamples by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeroCard(
            title = stringResource(R.string.speakers_hero_title),
            body = stringResource(R.string.speakers_hero_body),
        )

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusItem(stringResource(R.string.speaker_runtime), LocalContext.current.getString(uiState.runtimeDiagnostics.speakerRuntimeStatusResId))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.display_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.keep_raw_enrollment_audio))
                    Switch(checked = keepSamples, onCheckedChange = { keepSamples = it })
                }
                FilledTonalButton(
                    onClick = {
                        onCreateProfile(name, keepSamples)
                        name = ""
                        keepSamples = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.create_speaker_profile))
                }
            }
        }

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 200.dp, max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            items(uiState.speakerProfiles) { profile ->
                Card {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(profile.displayName, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.enrollment_count, profile.enrollmentCount))
                        Text(
                            if (profile.keepsAudioSamples) {
                                stringResource(R.string.audio_retained)
                            } else {
                                stringResource(R.string.audio_not_retained)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveMeetingScreen(
    uiState: MeetingAssistantUiState,
    draftTitle: String,
    onDraftTitleChange: (String) -> Unit,
    draftAgenda: String,
    onDraftAgendaChange: (String) -> Unit,
    onStartMeeting: (String, String) -> Unit,
    onStopMeeting: () -> Unit,
    onToggleSummaries: () -> Unit,
    onRenameSpeaker: (String, String) -> Unit,
    onLockSpeakerIdentity: (String, String) -> Unit,
) {
    var oldLabel by remember { mutableStateOf("Speaker A") }
    var newLabel by remember { mutableStateOf("") }
    var lockLabel by remember { mutableStateOf("Speaker A") }
    var lockName by remember { mutableStateOf(uiState.speakerProfiles.firstOrNull()?.displayName.orEmpty()) }
    val meeting = uiState.latestMeeting
    val activeMeeting = meeting?.takeIf {
        it.meeting.status == MeetingStatus.LIVE || it.meeting.status == MeetingStatus.PROCESSING
    }
    val diag = uiState.runtimeDiagnostics

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HeroCard(
            title = stringResource(R.string.live_hero_title),
            body = stringResource(R.string.live_hero_body),
        )

        if (activeMeeting == null) {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatusItem(stringResource(R.string.asr), LocalContext.current.getString(diag.sherpaStatusResId))
                    StatusItem(stringResource(R.string.llm), LocalContext.current.getString(diag.llmStatusResId))
                    OutlinedTextField(
                        value = draftTitle,
                        onValueChange = onDraftTitleChange,
                        label = { Text(stringResource(R.string.meeting_title)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draftAgenda,
                        onValueChange = onDraftAgendaChange,
                        label = { Text(stringResource(R.string.agenda_or_focus)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { onStartMeeting(draftTitle, draftAgenda) },
                        enabled = uiState.permissions.canStartMeeting,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.start_meeting))
                    }
                    if (!uiState.permissions.canStartMeeting) {
                        Text(
                            text = stringResource(R.string.mic_permission_missing),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        } else {
            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        activeMeeting.meeting.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    StatusItem(stringResource(R.string.meeting_status), activeMeeting.meeting.status.name)
                    StatusItem(stringResource(R.string.recognizer), uiState.recognizerLabel)
                    StatusItem(
                        stringResource(R.string.live_summaries),
                        if (activeMeeting.meeting.summariesPaused) stringResource(R.string.paused) else stringResource(R.string.running),
                    )
                    StatusItem(
                        stringResource(R.string.speaker_identification),
                        if (uiState.speakerIdentificationEnabled) stringResource(R.string.enabled) else stringResource(R.string.temporarily_degraded),
                    )
                    StatusItem(
                        stringResource(R.string.summary_runtime),
                        if (uiState.liveSummariesEnabled) stringResource(R.string.enabled) else stringResource(R.string.temporarily_degraded),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onToggleSummaries) {
                            Text(
                                if (activeMeeting.meeting.summariesPaused) {
                                    stringResource(R.string.resume_live_notes)
                                } else {
                                    stringResource(R.string.pause_live_notes)
                                },
                            )
                        }
                        FilledTonalButton(onClick = onStopMeeting) {
                            Text(stringResource(R.string.stop_meeting))
                        }
                    }
                }
            }

            Card {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.manual_speaker_corrections), fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = oldLabel,
                        onValueChange = { oldLabel = it },
                        label = { Text(stringResource(R.string.current_speaker_label)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newLabel,
                        onValueChange = { newLabel = it },
                        label = { Text(stringResource(R.string.new_label)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { onRenameSpeaker(oldLabel, newLabel) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.rename_speaker_label))
                    }
                    OutlinedTextField(
                        value = lockLabel,
                        onValueChange = { lockLabel = it },
                        label = { Text(stringResource(R.string.speaker_label_to_lock)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = lockName,
                        onValueChange = { lockName = it },
                        label = { Text(stringResource(R.string.known_profile_name)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    FilledTonalButton(
                        onClick = {
                            uiState.speakerProfiles.firstOrNull { it.displayName == lockName }?.let { profile ->
                                onLockSpeakerIdentity(lockLabel, profile.id)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.lock_label_to_profile))
                    }
                }
            }

            Text(
                text = stringResource(R.string.transcript_stream),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp, max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 160.dp),
            ) {
                items(activeMeeting.transcriptSegments) { segment ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("${segment.speakerLabel}  ${segment.startMs}ms-${segment.endMs}ms")
                            Text(segment.text)
                            Text(
                                text = stringResource(
                                    R.string.asr_confidence,
                                    (segment.asrConfidence * 100).toInt(),
                                    (segment.speakerConfidence * 100).toInt(),
                                    if (segment.isFinal) stringResource(R.string.transcript_confirmed) else stringResource(R.string.live_tag),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewScreen(
    uiState: MeetingAssistantUiState,
    onExport: (ExportFormat) -> Unit,
) {
    val summary = uiState.latestMeeting?.meetingSummary
    val chunks = uiState.latestMeeting?.chunkSummaries.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeroCard(
            title = stringResource(R.string.review_hero_title),
            body = stringResource(R.string.review_hero_body),
        )

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.overview), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(summary?.overview?.text ?: stringResource(R.string.no_summary_yet))
            }
        }

        SummaryBulletSection(stringResource(R.string.decisions), summary?.decisions.orEmpty().map { it.text })
        SummaryBulletSection(stringResource(R.string.action_items), summary?.actionItems.orEmpty().map { "${it.owner}: ${it.task}" })
        SummaryBulletSection(stringResource(R.string.risks), summary?.risks.orEmpty().map { it.text })
        SummaryBulletSection(stringResource(R.string.open_questions), summary?.openQuestions.orEmpty().map { it.text })
        SummaryBulletSection(
            stringResource(R.string.speaker_notes),
            summary?.speakerNotes.orEmpty().map { "${it.displayName ?: it.speakerLabel}: ${it.note}" },
        )

        Card {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.chunk_summaries), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (chunks.isEmpty()) {
                    Text(stringResource(R.string.no_chunks_yet))
                } else {
                    chunks.forEach { chunk ->
                        Text("- ${chunk.headline}")
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onExport(ExportFormat.MARKDOWN) }) { Text(stringResource(R.string.export_markdown)) }
            FilledTonalButton(onClick = { onExport(ExportFormat.TEXT) }) { Text(stringResource(R.string.export_txt)) }
            FilledTonalButton(onClick = { onExport(ExportFormat.JSON) }) { Text(stringResource(R.string.export_json)) }
        }

        uiState.lastExportPath?.let { path ->
            Card {
                Text(
                    text = stringResource(R.string.last_export, path),
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun SummaryBulletSection(title: String, items: List<String>) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (items.isEmpty()) {
                Text(stringResource(R.string.nothing_generated_yet))
            } else {
                items.forEach { Text("- $it") }
            }
        }
    }
}

@Composable
private fun StatusItem(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun HeroCard(title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
