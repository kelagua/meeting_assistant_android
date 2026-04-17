package com.codex.meetingassistant.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.codex.meetingassistant.MeetingAssistantApp
import com.codex.meetingassistant.R
import com.codex.meetingassistant.data.model.Meeting
import kotlinx.coroutines.launch

class MeetingCaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                val meeting = intent.getParcelableExtraCompat<MeetingPayload>(EXTRA_MEETING)
                if (meeting != null) {
                    appContainer.sessionCoordinatorScope.launch {
                        appContainer.sessionCoordinator.start(meeting.toDomain())
                    }
                }
            }

            ACTION_STOP -> {
                appContainer.sessionCoordinatorScope.launch {
                    appContainer.sessionCoordinator.stop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_meeting_assistant)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .build()

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private val appContainer get() = (application as MeetingAssistantApp).container

    companion object {
        private const val CHANNEL_ID = "meeting_capture"
        private const val NOTIFICATION_ID = 101
        private const val EXTRA_MEETING = "extra_meeting"

        const val ACTION_START = "com.codex.meetingassistant.action.START"
        const val ACTION_STOP = "com.codex.meetingassistant.action.STOP"

        fun startIntent(context: Context, meeting: Meeting): Intent =
            Intent(context, MeetingCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MEETING, MeetingPayload.fromDomain(meeting))
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, MeetingCaptureService::class.java).apply {
                action = ACTION_STOP
            }
    }
}
