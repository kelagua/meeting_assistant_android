package com.codex.meetingassistant.runtime.benchmark

import com.codex.meetingassistant.data.model.DeviceProfile
import com.codex.meetingassistant.data.model.ModelPack
import com.codex.meetingassistant.data.model.ModelPackStatus
import com.codex.meetingassistant.data.model.SummaryStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkSelectorTest {
    @Test
    fun `choosePack prefers largest pack that fits device budget`() {
        val packs = listOf(
            testPack(id = "small", size = 1200, minRam = 8, minScore = 70),
            testPack(id = "medium", size = 1800, minRam = 10, minScore = 82),
            testPack(id = "large", size = 2600, minRam = 12, minScore = 90),
        )
        val profile = DeviceProfile(ramGb = 12, cpuCores = 8, abi = "arm64-v8a", score = 92)

        val selected = BenchmarkSelector.choosePack(profile, packs, SummaryStage.FINAL_SUMMARY)

        assertEquals("large", selected?.id)
    }

    @Test
    fun `buildScore rewards arm64 and bigger devices`() {
        val baseline = BenchmarkSelector.buildScore(ramGb = 8, cpuCores = 4, abi = "armeabi-v7a")
        val upgraded = BenchmarkSelector.buildScore(ramGb = 12, cpuCores = 8, abi = "arm64-v8a")

        assertTrue(upgraded > baseline)
    }

    private fun testPack(
        id: String,
        size: Int,
        minRam: Int,
        minScore: Int,
    ): ModelPack = ModelPack(
        id = id,
        displayName = id,
        description = id,
        stage = SummaryStage.FINAL_SUMMARY,
        quantization = "int4",
        estimatedSizeMb = size,
        assetPath = "assets/$id",
        status = ModelPackStatus.AVAILABLE,
        minRamGb = minRam,
        minScore = minScore,
        supportedLanguages = listOf("zh-CN", "en-US"),
    )
}
