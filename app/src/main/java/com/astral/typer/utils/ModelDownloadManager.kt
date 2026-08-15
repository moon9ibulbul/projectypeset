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

    private val _lamaFp16State = MutableStateFlow(DownloadState())
    val lamaFp16State: StateFlow<DownloadState> = _lamaFp16State.asStateFlow()

    private val _lamaInt8State = MutableStateFlow(DownloadState())
    val lamaInt8State: StateFlow<DownloadState> = _lamaInt8State.asStateFlow()

    private val _bubbleState = MutableStateFlow(DownloadState())
    val bubbleState: StateFlow<DownloadState> = _bubbleState.asStateFlow()

    private val _bubbleInt8State = MutableStateFlow(DownloadState())
    val bubbleInt8State: StateFlow<DownloadState> = _bubbleInt8State.asStateFlow()

    private val _miganState = MutableStateFlow(DownloadState())
    val miganState: StateFlow<DownloadState> = _miganState.asStateFlow()

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
    fun startLamaFp16Download(context: Context) {
        if (_lamaFp16State.value.status == DownloadStatus.DOWNLOADING) return

        _lamaFp16State.value = DownloadState(DownloadStatus.DOWNLOADING, 0f)
        scope.launch {
            val processor = LaMaProcessor(context.applicationContext)
            var lastProgress = -1
            val success = processor.downloadFp16Model { progress ->
                val progressPercent = (progress * 100).toInt()
                if (progressPercent != lastProgress) {
                    lastProgress = progressPercent
                    _lamaFp16State.value = DownloadState(DownloadStatus.DOWNLOADING, progress)
                }
            }
            if (success) {
                _lamaFp16State.value = DownloadState(DownloadStatus.SUCCESS, 1f)
            } else {
                _lamaFp16State.value = DownloadState(DownloadStatus.FAILED, 0f)
            }
        }
    }

    @Synchronized
    fun startLamaInt8Download(context: Context) {
        if (_lamaInt8State.value.status == DownloadStatus.DOWNLOADING) return

        _lamaInt8State.value = DownloadState(DownloadStatus.DOWNLOADING, 0f)
        scope.launch {
            val processor = LaMaProcessor(context.applicationContext)
            var lastProgress = -1
            val success = processor.downloadInt8Model { progress ->
                val progressPercent = (progress * 100).toInt()
                if (progressPercent != lastProgress) {
                    lastProgress = progressPercent
                    _lamaInt8State.value = DownloadState(DownloadStatus.DOWNLOADING, progress)
                }
            }
            if (success) {
                _lamaInt8State.value = DownloadState(DownloadStatus.SUCCESS, 1f)
            } else {
                _lamaInt8State.value = DownloadState(DownloadStatus.FAILED, 0f)
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

    @Synchronized
    fun startBubbleInt8Download(context: Context) {
        if (_bubbleInt8State.value.status == DownloadStatus.DOWNLOADING) return

        _bubbleInt8State.value = DownloadState(DownloadStatus.DOWNLOADING, 0f)
        scope.launch {
            val processor = BubbleDetectorProcessor(context.applicationContext)
            var lastProgress = -1
            val success = processor.downloadInt8Model { progress ->
                val progressPercent = (progress * 100).toInt()
                if (progressPercent != lastProgress) {
                    lastProgress = progressPercent
                    _bubbleInt8State.value = DownloadState(DownloadStatus.DOWNLOADING, progress)
                }
            }
            if (success) {
                _bubbleInt8State.value = DownloadState(DownloadStatus.SUCCESS, 1f)
            } else {
                _bubbleInt8State.value = DownloadState(DownloadStatus.FAILED, 0f)
            }
        }
    }

    fun isLamaDownloading(): Boolean {
        return _lamaState.value.status == DownloadStatus.DOWNLOADING
    }

    fun isLamaFp16Downloading(): Boolean {
        return _lamaFp16State.value.status == DownloadStatus.DOWNLOADING
    }

    fun isLamaInt8Downloading(): Boolean {
        return _lamaInt8State.value.status == DownloadStatus.DOWNLOADING
    }

    fun isBubbleDownloading(): Boolean {
        return _bubbleState.value.status == DownloadStatus.DOWNLOADING
    }

    fun isBubbleInt8Downloading(): Boolean {
        return _bubbleInt8State.value.status == DownloadStatus.DOWNLOADING
    }

    @Synchronized
    fun startMiganDownload(context: Context) {
        if (_miganState.value.status == DownloadStatus.DOWNLOADING) return

        _miganState.value = DownloadState(DownloadStatus.DOWNLOADING, 0f)
        scope.launch {
            val processor = MiganProcessor(context.applicationContext)
            var lastProgress = -1
            val success = processor.downloadModel { progress ->
                val progressPercent = (progress * 100).toInt()
                if (progressPercent != lastProgress) {
                    lastProgress = progressPercent
                    _miganState.value = DownloadState(DownloadStatus.DOWNLOADING, progress)
                }
            }
            if (success) {
                _miganState.value = DownloadState(DownloadStatus.SUCCESS, 1f)
            } else {
                _miganState.value = DownloadState(DownloadStatus.FAILED, 0f)
            }
        }
    }

    fun isMiganDownloading(): Boolean {
        return _miganState.value.status == DownloadStatus.DOWNLOADING
    }
}
