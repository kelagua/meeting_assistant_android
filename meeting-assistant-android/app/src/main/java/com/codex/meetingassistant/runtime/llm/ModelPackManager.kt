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
        val localPacks = packs.filter { it.stage == stage && !it.localFilePath.isNullOrBlank() }
        return if (localPacks.isNotEmpty()) {
            benchmarkSelector.choosePack(
                modelPacks = localPacks,
                stage = stage,
                preferredPackId = preferred,
            ) ?: benchmarkSelector.choosePack(
                modelPacks = packs,
                stage = stage,
                preferredPackId = preferred,
            )
        } else {
            val anyLocalPack = chooseBestLocalPack(packs, preferred)
            anyLocalPack ?: benchmarkSelector.choosePack(
                modelPacks = packs,
                stage = stage,
                preferredPackId = preferred,
            )
        }
    }

    private fun chooseBestLocalPack(
        packs: List<ModelPack>,
        preferredPackId: String?,
    ): ModelPack? {
        val localPacks = packs.filter { !it.localFilePath.isNullOrBlank() }
        if (localPacks.isEmpty()) return null

        preferredPackId?.let { preferredId ->
            localPacks.firstOrNull { it.id == preferredId }?.let { return it }
        }

        val profile = benchmarkSelector.profile()
        return localPacks
            .filter { pack -> profile.ramGb >= pack.minRamGb && profile.score >= pack.minScore }
            .maxByOrNull { it.estimatedSizeMb }
            ?: localPacks.minByOrNull { it.estimatedSizeMb }
    }
}
