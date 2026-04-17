package com.codex.meetingassistant.runtime.speaker

import com.codex.meetingassistant.data.model.SpeakerAssignment
import com.codex.meetingassistant.data.model.SpeakerProfile

interface SpeakerIdentificationEngine {
    suspend fun match(
        meetingId: String,
        speakerLabel: String,
        profiles: List<SpeakerProfile>,
    ): SpeakerAssignment?
}

class DemoSpeakerIdentificationEngine : SpeakerIdentificationEngine {
    override suspend fun match(
        meetingId: String,
        speakerLabel: String,
        profiles: List<SpeakerProfile>,
    ): SpeakerAssignment? {
        if (profiles.isEmpty()) return null
        val profile = profiles[speakerLabel.last().code % profiles.size]
        return SpeakerAssignment(
            id = "${meetingId}_$speakerLabel",
            meetingId = meetingId,
            speakerLabel = speakerLabel,
            profileId = profile.id,
            confidence = 0.74f,
            lockedByUser = false,
            updatedAtEpochMs = System.currentTimeMillis(),
        )
    }
}

class SherpaSpeakerIdentificationEngine : SpeakerIdentificationEngine {
    override suspend fun match(
        meetingId: String,
        speakerLabel: String,
        profiles: List<SpeakerProfile>,
    ): SpeakerAssignment? {
        // TODO: Wire sherpa-onnx speaker embedding / verification here.
        return null
    }
}
