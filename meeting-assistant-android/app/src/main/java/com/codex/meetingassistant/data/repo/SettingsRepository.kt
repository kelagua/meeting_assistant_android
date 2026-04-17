package com.codex.meetingassistant.data.repo

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.codex.meetingassistant.data.model.MeetingPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "meeting_assistant_settings")

class SettingsRepository(private val context: Context) {
    private val intervalKey = intPreferencesKey("live_summary_interval_minutes")
    private val charTargetKey = intPreferencesKey("live_summary_character_target")
    private val selectedLivePackKey = stringPreferencesKey("selected_live_pack")
    private val selectedFinalPackKey = stringPreferencesKey("selected_final_pack")

    val preferences: Flow<MeetingPreferences> = context.settingsDataStore.data.map { prefs ->
        MeetingPreferences(
            liveSummaryIntervalMinutes = prefs[intervalKey] ?: 3,
            liveSummaryCharacterTarget = prefs[charTargetKey] ?: 650,
            selectedLivePackId = prefs[selectedLivePackKey],
            selectedFinalPackId = prefs[selectedFinalPackKey],
        )
    }

    suspend fun updateSelectedPack(stageKey: String, packId: String) {
        context.settingsDataStore.edit { prefs ->
            when (stageKey) {
                "live" -> prefs[selectedLivePackKey] = packId
                "final" -> prefs[selectedFinalPackKey] = packId
            }
        }
    }
}
