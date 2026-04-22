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
import kotlinx.coroutines.launch

class ModelDownloadService : Service() {

    private val downloadManager by lazy { appContainer.modelDownloadManager }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val modelPackId = intent.getStringExtra(EXTRA_MODEL_PACK_ID) ?: run {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val downloadUrl = intent.getStringExtra(EXTRA_DOWNLOAD_URL) ?: run {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val totalBytes = intent.getLongExtra(EXTRA_TOTAL_BYTES, 0L)

                val notification = buildNotification(modelPackId, 0f, "准备下载...")
                startForeground(NOTIFICATION_ID, notification)

                appContainer.downloadScope.launch {
                    downloadManager.startDownload(modelPackId, downloadUrl, totalBytes)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }

            ACTION_CANCEL -> {
                val modelPackId = intent.getStringExtra(EXTRA_MODEL_PACK_ID)
                if (modelPackId != null) {
                    appContainer.downloadScope.launch {
                        downloadManager.cancelDownload(modelPackId)
                    }
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    fun updateProgressNotification(modelPackId: String, progress: Float, text: String) {
        val notification = buildNotification(modelPackId, progress, text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        modelPackId: String,
        progress: Float,
        text: String,
    ): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_meeting_assistant)
            .setContentTitle("下载模型中")
            .setContentText(text)
            .setProgress(100, progress.toInt(), progress <= 0f)
            .setOngoing(true)
            .setSilent(true)

        return builder.build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_download_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_download_description)
        }
        manager.createNotificationChannel(channel)
    }

    private val appContainer get() = (application as MeetingAssistantApp).container

    companion object {
        private const val CHANNEL_ID = "model_download"
        private const val NOTIFICATION_ID = 102
        private const val EXTRA_MODEL_PACK_ID = "extra_model_pack_id"
        private const val EXTRA_DOWNLOAD_URL = "extra_download_url"
        private const val EXTRA_TOTAL_BYTES = "extra_total_bytes"

        const val ACTION_START = "com.codex.meetingassistant.action.DOWNLOAD_START"
        const val ACTION_CANCEL = "com.codex.meetingassistant.action.DOWNLOAD_CANCEL"

        fun startIntent(
            context: Context,
            modelPackId: String,
            downloadUrl: String,
            totalBytes: Long,
        ): Intent = Intent(context, ModelDownloadService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_MODEL_PACK_ID, modelPackId)
            putExtra(EXTRA_DOWNLOAD_URL, downloadUrl)
            putExtra(EXTRA_TOTAL_BYTES, totalBytes)
        }

        fun cancelIntent(context: Context, modelPackId: String): Intent =
            Intent(context, ModelDownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_MODEL_PACK_ID, modelPackId)
            }
    }
}
