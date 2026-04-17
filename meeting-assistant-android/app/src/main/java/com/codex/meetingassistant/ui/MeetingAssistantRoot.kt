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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codex.meetingassistant.data.model.ExportFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeetingAssistantRoot(viewModel: MeetingViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    LaunchedEffect(uiState.statusMessage) {
        uiState.statusMessage?.let { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Meeting Assistant") },
                actions = {
                    AssistChip(
                        onClick = { viewModel.selectTab(AppTab.Setup) },
                        label = { Text(uiState.recognizerLabel) },
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
                        icon = {
                            when (tab) {
                                AppTab.Setup -> androidx.compose.material3.Icon(Icons.Outlined.Dashboard, null)
                                AppTab.Speakers -> androidx.compose.material3.Icon(Icons.Outlined.Groups, null)
                                AppTab.Live -> androidx.compose.material3.Icon(Icons.Outlined.GraphicEq, null)
                                AppTab.Review -> androidx.compose.material3.Icon(Icons.Outlined.Description, null)
                            }
                        },
                        label = { Text(tab.label) },
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
                    requestPermissions = {
                        val permissions = buildList {
                            add(Manifest.permission.RECORD_AUDIO)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        permissionLauncher.launch(permissions.toTypedArray())
                    },
                )

                AppTab.Speakers -> SpeakerProfilesScreen(
                    uiState = uiState,
                    onCreateProfile = viewModel::createSpeakerProfile,
                )

                AppTab.Live -> LiveMeetingScreen(
                    uiState = uiState,
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
            }
        }
    }
}

@Composable
private fun SetupScreen(
    uiState: MeetingAssistantUiState,
    requestPermissions: () -> Unit,
) {
    val profile = uiState.deviceProfile
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeroCard(
            title = "纯离线旗舰机 MVP",
            body = "Android 12+，本地录音、实时转写、说话人映射和会后纪要。默认优先保住录音与 ASR，再降会中摘要与声纹。"
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("设备基准", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("RAM: ${profile?.ramGb ?: "--"} GB")
                Text("CPU cores: ${profile?.cpuCores ?: "--"}")
                Text("ABI: ${profile?.abi ?: "--"}")
                Text("Score: ${profile?.score ?: "--"}")
            }
        }
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("模型包建议", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                uiState.modelPacks.forEach { pack ->
                    Text("• ${pack.displayName} · ${pack.quantization} · ${pack.estimatedSizeMb}MB")
                }
            }
        }
        Button(onClick = requestPermissions, modifier = Modifier.fillMaxWidth()) {
            Text("请求录音与通知权限")
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
            title = "会前声纹录入",
            body = "首版默认只保存 embedding 和显示名。打开“保留样本”时，后续可扩展为保留 enrollment 音频。"
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("参会人姓名") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("保留 enrollment 原样本")
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
            Text("新增本地声纹档案")
        }
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            items(uiState.speakerProfiles) { profile ->
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(profile.displayName, fontWeight = FontWeight.SemiBold)
                        Text("录入次数: ${profile.enrollmentCount}")
                        Text("保留样本: ${if (profile.keepsAudioSamples) "是" else "否"}")
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveMeetingScreen(
    uiState: MeetingAssistantUiState,
    onStartMeeting: (String, String) -> Unit,
    onStopMeeting: () -> Unit,
    onToggleSummaries: () -> Unit,
    onRenameSpeaker: (String, String) -> Unit,
    onLockSpeakerIdentity: (String, String) -> Unit,
) {
    var title by remember { mutableStateOf("产品周会") }
    var agenda by remember { mutableStateOf("本地转写、声纹识别、会后总结") }
    var oldLabel by remember { mutableStateOf("Speaker A") }
    var newLabel by remember { mutableStateOf("") }
    var lockLabel by remember { mutableStateOf("Speaker A") }
    var lockName by remember { mutableStateOf(uiState.speakerProfiles.firstOrNull()?.displayName.orEmpty()) }
    val meeting = uiState.latestMeeting

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        HeroCard(
            title = "实时会议页",
            body = "实时稿先展示低延迟版本，结束后由 final transcript 覆盖。默认允许先匿名分离，再手动修正说话人。"
        )
        if (meeting == null || meeting.meeting.status.name == "COMPLETED") {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("会议标题") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = agenda,
                onValueChange = { agenda = it },
                label = { Text("会议议程") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { onStartMeeting(title, agenda) }, modifier = Modifier.fillMaxWidth()) {
                Text("开始会议")
            }
        } else {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(meeting.meeting.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("状态: ${meeting.meeting.status}")
                    Text("会中提纲: ${if (meeting.meeting.summariesPaused) "已暂停" else "运行中"}")
                    Text("声纹映射: ${if (uiState.speakerIdentificationEnabled) "开启" else "已降级暂停"}")
                    Text("会中总结: ${if (uiState.liveSummariesEnabled) "开启" else "已降级暂停"}")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = onToggleSummaries) { Text("暂停/恢复提纲") }
                        FilledTonalButton(onClick = onStopMeeting) { Text("结束会议") }
                    }
                }
            }

            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("手动改名与锁定身份", fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = oldLabel,
                        onValueChange = { oldLabel = it },
                        label = { Text("原 speaker label") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = newLabel,
                        onValueChange = { newLabel = it },
                        label = { Text("新的显示名/label") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { onRenameSpeaker(oldLabel, newLabel) }, modifier = Modifier.fillMaxWidth()) {
                        Text("重命名说话人")
                    }
                    OutlinedTextField(
                        value = lockLabel,
                        onValueChange = { lockLabel = it },
                        label = { Text("要锁定的 speaker label") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = lockName,
                        onValueChange = { lockName = it },
                        label = { Text("锁定到已录入姓名") },
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
                        Text("锁定身份映射")
                    }
                }
            }

            Text("实时字幕", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 160.dp),
            ) {
                items(meeting.transcriptSegments) { segment ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${segment.speakerLabel} · ${segment.startMs}ms-${segment.endMs}ms")
                            Text(segment.text)
                            Text(
                                "ASR ${(segment.asrConfidence * 100).toInt()}% · Speaker ${(segment.speakerConfidence * 100).toInt()}% · ${if (segment.isFinal) "final" else "live"}",
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
            title = "会后纪要与导出",
            body = "结构化纪要保留 overview、decisions、action items、risks、open questions 和 speaker notes，并支持回链 transcript 段落。"
        )
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Overview", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(summary?.overview?.text ?: "会议结束后会在这里生成最终纪要。")
            }
        }
        SummaryBulletSection("Decisions", summary?.decisions.orEmpty().map { it.text })
        SummaryBulletSection("Action Items", summary?.actionItems.orEmpty().map { "${it.owner}: ${it.task}" })
        SummaryBulletSection("Risks", summary?.risks.orEmpty().map { it.text })
        SummaryBulletSection("Open Questions", summary?.openQuestions.orEmpty().map { it.text })
        SummaryBulletSection("Speaker Notes", summary?.speakerNotes.orEmpty().map {
            "${it.displayName ?: it.speakerLabel}: ${it.note}"
        })

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("会中提纲分片", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                chunks.forEach { chunk ->
                    Text("• ${chunk.headline}")
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onExport(ExportFormat.MARKDOWN) }) { Text("导出 Markdown") }
            FilledTonalButton(onClick = { onExport(ExportFormat.TEXT) }) { Text("导出 TXT") }
            FilledTonalButton(onClick = { onExport(ExportFormat.JSON) }) { Text("导出 JSON") }
        }
        uiState.lastExportPath?.let { path ->
            Card {
                Text(
                    text = "最近导出: $path",
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun SummaryBulletSection(title: String, items: List<String>) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (items.isEmpty()) {
                Text("暂无内容")
            } else {
                items.forEach { Text("• $it") }
            }
        }
    }
}

@Composable
private fun HeroCard(title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
