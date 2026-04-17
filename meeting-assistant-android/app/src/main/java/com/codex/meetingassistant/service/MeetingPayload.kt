package com.codex.meetingassistant.service

import android.content.Intent
import android.os.Parcelable
import com.codex.meetingassistant.data.model.Meeting
import com.codex.meetingassistant.data.model.MeetingStatus
import kotlinx.parcelize.Parcelize

@Parcelize
data class MeetingPayload(
    val id: String,
    val title: String,
    val agenda: String,
    val status: String,
    val createdAtEpochMs: Long,
    val startedAtEpochMs: Long?,
    val endedAtEpochMs: Long?,
    val summariesPaused: Boolean,
) : Parcelable {
    fun toDomain(): Meeting = Meeting(
        id = id,
        title = title,
        agenda = agenda,
        status = MeetingStatus.valueOf(status),
        createdAtEpochMs = createdAtEpochMs,
        startedAtEpochMs = startedAtEpochMs,
        endedAtEpochMs = endedAtEpochMs,
        summariesPaused = summariesPaused,
    )

    companion object {
        fun fromDomain(meeting: Meeting): MeetingPayload = MeetingPayload(
            id = meeting.id,
            title = meeting.title,
            agenda = meeting.agenda,
            status = meeting.status.name,
            createdAtEpochMs = meeting.createdAtEpochMs,
            startedAtEpochMs = meeting.startedAtEpochMs,
            endedAtEpochMs = meeting.endedAtEpochMs,
            summariesPaused = meeting.summariesPaused,
        )
    }
}

inline fun <reified T : Parcelable> Intent.getParcelableExtraCompat(name: String): T? {
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(name, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableExtra(name)
    }
}
