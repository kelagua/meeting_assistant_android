package com.codex.meetingassistant.runtime.benchmark

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.codex.meetingassistant.data.model.DeviceProfile
import com.codex.meetingassistant.data.model.ModelPack
import com.codex.meetingassistant.data.model.SummaryStage
import kotlin.math.max

class BenchmarkSelector(private val context: Context) {
    fun profile(): DeviceProfile {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val ramGb = max(1, (memoryInfo.totalMem / (1024L * 1024L * 1024L)).toInt())
        val cpuCores = Runtime.getRuntime().availableProcessors()
        val abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        val score = buildScore(ramGb = ramGb, cpuCores = cpuCores, abi = abi)
        return DeviceProfile(ramGb = ramGb, cpuCores = cpuCores, abi = abi, score = score)
    }

    fun choosePack(
        modelPacks: List<ModelPack>,
        stage: SummaryStage,
        preferredPackId: String? = null,
    ): ModelPack? = choosePack(profile(), modelPacks, stage, preferredPackId)

    companion object {
        fun choosePack(
            profile: DeviceProfile,
            modelPacks: List<ModelPack>,
            stage: SummaryStage,
            preferredPackId: String? = null,
        ): ModelPack? {
            preferredPackId?.let { preferredId ->
                modelPacks.firstOrNull { it.id == preferredId && it.stage == stage }?.let { return it }
            }

            return modelPacks
                .filter { it.stage == stage }
                .sortedWith(compareBy<ModelPack> { it.estimatedSizeMb }.thenBy { it.minScore })
                .filter { pack -> profile.ramGb >= pack.minRamGb && profile.score >= pack.minScore }
                .maxByOrNull { it.estimatedSizeMb }
                ?: modelPacks.filter { it.stage == stage }.minByOrNull { it.estimatedSizeMb }
        }

        fun buildScore(ramGb: Int, cpuCores: Int, abi: String): Int {
            val abiBonus = when {
                abi.contains("arm64", ignoreCase = true) -> 18
                abi.contains("x86_64", ignoreCase = true) -> 14
                else -> 8
            }
            return ramGb * 8 + cpuCores * 4 + abiBonus
        }
    }
}
