package com.codex.meetingassistant.runtime.llm

import android.content.Context
import com.codex.meetingassistant.data.model.DownloadStatus
import com.codex.meetingassistant.data.model.DownloadTask
import com.codex.meetingassistant.data.repo.ModelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.supervisorScope
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ModelDownloadManager(
    private val context: Context,
    private val repository: ModelRepository,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // 无超时，允许大文件下载
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val _downloadTasks = MutableStateFlow<Map<String, DownloadTask>>(emptyMap())
    val downloadTasks: StateFlow<Map<String, DownloadTask>> = _downloadTasks.asStateFlow()

    private val mutex = Mutex()
    // 记录当前正在执行的 OkHttp Call，以便 cancel 时可以中断
    private var currentCall: Call? = null
    // 取消标记，cancelDownload 设置后 doDownloadFile 每次读取时检查
    private val cancelled = AtomicBoolean(false)

    suspend fun startDownload(
        modelPackId: String,
        downloadUrl: String,
        totalBytes: Long,
    ) = mutex.withLock {
        cancelled.set(false)
        val task = DownloadTask(
            modelPackId = modelPackId,
            downloadUrl = downloadUrl,
            totalBytes = totalBytes,
            status = DownloadStatus.PENDING,
            startedAt = System.currentTimeMillis(),
        )
        _downloadTasks.value = _downloadTasks.value + (modelPackId to task)

        try {
            repository.startDownload(modelPackId, downloadUrl, totalBytes)

            _downloadTasks.value = _downloadTasks.value + (modelPackId to task.copy(
                status = DownloadStatus.DOWNLOADING,
            ))
            repository.updateDownloadProgress(modelPackId, 0L, DownloadStatus.DOWNLOADING)

            downloadFile(modelPackId, downloadUrl, totalBytes)

            val localPath = getModelFilePath(modelPackId)
            _downloadTasks.value = _downloadTasks.value + (modelPackId to task.copy(
                status = DownloadStatus.COMPLETED,
                bytesDownloaded = totalBytes,
                localFilePath = localPath,
                completedAt = System.currentTimeMillis(),
            ))
            repository.completeDownload(modelPackId, localPath)

        } catch (e: CancellationException) {
            // 取消不视为错误
            cleanupCancelledDownload(modelPackId)
        } catch (e: Exception) {
            e.printStackTrace()
            val isAlreadyCancelled = cancelled.get()
            if (!isAlreadyCancelled) {
                _downloadTasks.value = _downloadTasks.value + (modelPackId to task.copy(
                    status = DownloadStatus.FAILED,
                ))
                repository.failDownload(modelPackId)
            }
        } finally {
            currentCall = null
        }
    }

    private suspend fun cleanupCancelledDownload(modelPackId: String) {
        val partialFile = File(getModelDir(), "${modelPackId}.litertlm")
        if (partialFile.exists()) {
            partialFile.delete()
        }
        _downloadTasks.value = _downloadTasks.value - modelPackId
        repository.cancelDownload(modelPackId)
    }

    private suspend fun downloadFile(
        modelPackId: String,
        downloadUrl: String,
        totalBytes: Long,
    ) {
        val official = "https://huggingface.co"
        val mirror = "https://hf-mirror.com"
        // 优先尝试官方源，VPN/代理拦截时切换镜像
        val fallbackUrl = if (downloadUrl.contains(official)) {
            downloadUrl.replace(official, mirror)
        } else {
            downloadUrl.replace(mirror, official)
        }
        val urls = listOf(downloadUrl, fallbackUrl).distinct()

        var lastException: Exception? = null
        for (url in urls) {
            if (cancelled.get()) throw CancellationException("Cancelled")
            try {
                doDownloadFile(modelPackId, url, totalBytes)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                e.printStackTrace()
            }
        }
        throw lastException ?: Exception("Download failed for all URLs")
    }

    private suspend fun doDownloadFile(
        modelPackId: String,
        downloadUrl: String,
        totalBytes: Long,
    ) {
        if (cancelled.get()) throw CancellationException("Cancelled before start")

        val request = Request.Builder()
            .url(downloadUrl)
            .build()
        val call = client.newCall(request)
        currentCall = call

        val response = call.execute()
        if (!response.isSuccessful) {
            throw Exception("Download failed: ${response.code}")
        }

        val body = response.body ?: throw Exception("Empty response body")
        val file = File(getModelDir(), "${modelPackId}.litertlm")

        FileOutputStream(file).use { output ->
            body.source().use { source ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalDownloaded = 0L

                while (source.read(buffer).also { bytesRead = it } != -1) {
                    // 每次读取前检查取消标记
                    if (cancelled.get()) {
                        throw CancellationException("Cancelled during download")
                    }

                    output.write(buffer, 0, bytesRead)
                    totalDownloaded += bytesRead

                    _downloadTasks.value = _downloadTasks.value.toMutableMap().apply {
                        put(modelPackId, DownloadTask(
                            modelPackId = modelPackId,
                            downloadUrl = downloadUrl,
                            totalBytes = totalBytes,
                            bytesDownloaded = totalDownloaded,
                            status = DownloadStatus.DOWNLOADING,
                            startedAt = _downloadTasks.value[modelPackId]?.startedAt ?: 0L,
                        ))
                    }
                    repository.updateDownloadProgress(
                        modelPackId,
                        totalDownloaded,
                        DownloadStatus.DOWNLOADING,
                    )
                }
            }
        }
    }

    suspend fun cancelDownload(modelPackId: String) {
        // 1. 中断 OkHttp 网络请求
        currentCall?.cancel()
        // 2. 设置取消标记，downloadFile/doDownloadFile 的下次检查会抛出 CancellationException
        cancelled.set(true)
        // 3. 等待互斥锁后更新 UI 状态
        mutex.withLock {
            val currentTask = _downloadTasks.value[modelPackId]
            currentTask?.let { task ->
                _downloadTasks.value = _downloadTasks.value + (modelPackId to task.copy(
                    status = DownloadStatus.CANCELLED,
                ))
            }
        }
    }

    suspend fun deleteModel(modelPackId: String) = mutex.withLock {
        currentCall?.takeIf { it.request().url.toString().contains(modelPackId) }?.cancel()
        val file = File(getModelDir(), "${modelPackId}.litertlm")
        if (file.exists()) {
            file.delete()
        }
        _downloadTasks.value = _downloadTasks.value - modelPackId
        repository.deleteModel(modelPackId)
    }

    private fun getModelDir(): File {
        val dir = File(context.filesDir, "models").apply { mkdirs() }
        return dir
    }

    fun getModelFilePath(modelPackId: String): String {
        return File(getModelDir(), "${modelPackId}.litertlm").absolutePath
    }
}
