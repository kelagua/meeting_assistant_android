package com.codex.meetingassistant.runtime.llm

import com.codex.meetingassistant.data.model.ModelPack
import com.codex.meetingassistant.data.model.SummaryStage
import com.codex.meetingassistant.data.repo.MeetingRepository
import com.codex.meetingassistant.data.repo.SettingsRepository
import com.codex.meetingassistant.runtime.benchmark.BenchmarkSelector
import kotlinx.coroutines.flow.first

class ModelPackManager(
    private val meetingRepository: MeetingRepository,
    private val settingsRepository: SettingsRepository,
    private val benchmarkSelector: BenchmarkSelector,
) {
    suspend fun ensureSeededPacks() {
        meetingRepository.seedDefaultModelPacks()
    }

    suspend fun recommendPack(stage: SummaryStage): ModelPack? {
        val preferences = settingsRepository.preferences.first()
        val packs = meetingRepository.modelPacks.first()
        val preferred = when (stage) {
            SummaryStage.LIVE_NOTES -> preferences.selectedLivePackId
            SummaryStage.FINAL_SUMMARY -> preferences.selectedFinalPackId
        }
        return benchmarkSelector.choosePack(
            modelPacks = packs,
            stage = stage,
            preferredPackId = preferred,
        )
    }
}
