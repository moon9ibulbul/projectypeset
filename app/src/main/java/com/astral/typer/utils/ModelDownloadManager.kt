package com.astral.typer.utils

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class DownloadStatus {
    IDLE, DOWNLOADING, SUCCESS, FAILED
}

data class DownloadState(
    val status: DownloadStatus = DownloadStatus.IDLE,
    val progress: Float = 0f
)

object ModelDownloadManager {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _lamaState = MutableStateFlow(DownloadState())
    val lamaState: StateFlow<DownloadState> = _lamaState.asStateFlow()

    private val _bubbleState = MutableStateFlow(DownloadState())
    val bubbleState: StateFlow<DownloadState> = _bubbleState.asStateFlow()

    @Synchronized
    fun startLamaDownload(context: Context) {
        if (_lamaState.value.status == DownloadStatus.DOWNLOADING) return

        _lamaState.value = DownloadState(DownloadStatus.DOWNLOADING, 0f)
        scope.launch {
            val processor = LaMaProcessor(context.applicationContext)
            var lastProgress = -1
            val success = processor.downloadModel { progress ->
                val progressPercent = (progress * 100).toInt()
                if (progressPercent != lastProgress) {
                    lastProgress = progressPercent
                    _lamaState.value = DownloadState(DownloadStatus.DOWNLOADING, progress)
                }
            }
            if (success) {
                _lamaState.value = DownloadState(DownloadStatus.SUCCESS, 1f)
            } else {
                _lamaState.value = DownloadState(DownloadStatus.FAILED, 0f)
            }
        }
    }

    @Synchronized
    fun startBubbleDownload(context: Context) {
        if (_bubbleState.value.status == DownloadStatus.DOWNLOADING) return

        _bubbleState.value = DownloadState(DownloadStatus.DOWNLOADING, 0f)
        scope.launch {
            val processor = BubbleDetectorProcessor(context.applicationContext)
            var lastProgress = -1
            val success = processor.downloadModel { progress ->
                val progressPercent = (progress * 100).toInt()
                if (progressPercent != lastProgress) {
                    lastProgress = progressPercent
                    _bubbleState.value = DownloadState(DownloadStatus.DOWNLOADING, progress)
                }
            }
            if (success) {
                _bubbleState.value = DownloadState(DownloadStatus.SUCCESS, 1f)
            } else {
                _bubbleState.value = DownloadState(DownloadStatus.FAILED, 0f)
            }
        }
    }

    fun isLamaDownloading(): Boolean {
        return _lamaState.value.status == DownloadStatus.DOWNLOADING
    }

    fun isBubbleDownloading(): Boolean {
        return _bubbleState.value.status == DownloadStatus.DOWNLOADING
    }
}
