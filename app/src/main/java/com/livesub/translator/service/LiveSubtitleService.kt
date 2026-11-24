package com.livesub.translator.service

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.livesub.translator.R
import com.livesub.translator.config.AppPreferences
import com.livesub.translator.config.AudioCaptureSource
import com.livesub.translator.engine.OfflineModelManager
import com.livesub.translator.engine.OfflineTranslatorEngine
import com.livesub.translator.engine.SherpaAsrSession
import com.livesub.translator.engine.TranscriptEvent
import com.livesub.translator.overlay.FloatingSubtitleOverlay
import com.livesub.translator.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class LiveSubtitleService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.livesub.translator.ACTION_START"
        const val ACTION_STOP = "com.livesub.translator.ACTION_STOP"
        const val ACTION_TOGGLE_LOCK = "com.livesub.translator.ACTION_TOGGLE_LOCK"
        const val ACTION_REFRESH_STYLE = "com.livesub.translator.ACTION_REFRESH_STYLE"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        private const val NOTIF_CHANNEL_ID = "livesub_foreground_channel"
        private const val NOTIF_ID = 1001

        private val _runningState = MutableStateFlow(false)
        val runningState: StateFlow<Boolean> = _runningState
    }

    private lateinit var prefs: AppPreferences
    private lateinit var modelManager: OfflineModelManager
    private val translatorEngine = OfflineTranslatorEngine()

    private var overlay: FloatingSubtitleOverlay? = null
    private var mediaProjection: MediaProjection? = null
    private var captureJob: Job? = null
    private var translateJob: Job? = null

    @Volatile
    private var isPaused = false

    private val transcriptFlow = MutableSharedFlow<TranscriptEvent>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        modelManager = OfflineModelManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> stopSelfCleanly()
            ACTION_TOGGLE_LOCK -> {
                val ov = overlay
                if (ov != null) {
                    ov.setTouchLocked(!ov.isLocked())
                }
            }
            ACTION_REFRESH_STYLE -> {
                overlay?.applyVisualPreferences()
            }
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        val hasProjectionGrant = (resultCode == Activity.RESULT_OK && resultData != null)
        val initialSource = if (hasProjectionGrant) {
            prefs.audioSource
        } else {
            AudioCaptureSource.MICROPHONE.also { prefs.audioSource = it }
        }

        // Android 14+ 严格要求在调用 getMediaProjection 前先按匹配类型调用 startForeground
        val fgsType = if (hasProjectionGrant) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
        }

        if (fgsType != 0) {
            startForeground(NOTIF_ID, buildNotification(isLocked = false), fgsType)
        } else {
            startForeground(NOTIF_ID, buildNotification(isLocked = false))
        }

        if (hasProjectionGrant && resultData != null) {
            runCatching { mediaProjection?.stop() }
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpManager.getMediaProjection(resultCode, resultData)?.also { mp ->
                mp.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        // 如果系统吊销了投屏令牌且当前处于内录模式，自动切为麦克风或优雅停止
                        if (prefs.audioSource == AudioCaptureSource.INTERNAL_AUDIO && _runningState.value) {
                            stopSelfCleanly()
                        }
                    }
                }, null)
            }
        }

        if (overlay == null) {
            overlay = FloatingSubtitleOverlay(
                context = this,
                prefs = prefs,
                onSwitchAudioSource = { switchAudioSourceLive() },
                onTogglePause = {
                    isPaused = !isPaused
                    if (isPaused) {
                        overlay?.updateStatusHint("已暂停识别", "点击「▶ 继续」恢复实时字幕")
                    } else {
                        overlay?.updateStatusHint("已恢复监听", "正在实时识别视频语音...")
                    }
                    isPaused
                },
                onLockStateChanged = { locked ->
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(NOTIF_ID, buildNotification(isLocked = locked))
                },
                onRequestStop = { stopSelfCleanly() }
            ).also { it.show() }
        }

        _runningState.value = true
        isPaused = false
        startTranslationConsumer()
        restartAudioPipeline(initialSource)
    }

    private fun switchAudioSourceLive() {
        val next = if (prefs.audioSource == AudioCaptureSource.INTERNAL_AUDIO) {
            AudioCaptureSource.MICROPHONE
        } else {
            if (mediaProjection == null) {
                overlay?.updateStatusHint(
                    "尚未授予系统内录权限",
                    "请返回灵译字幕主页重新点击启动以授权系统内录"
                )
                return
            }
            AudioCaptureSource.INTERNAL_AUDIO
        }
        prefs.audioSource = next
        overlay?.updateAudioSourceBadge(next)
        overlay?.updateStatusHint(
            "已切换至：${next.label}",
            if (next == AudioCaptureSource.MICROPHONE) {
                "正在通过麦克风收音（请调大手机外放音量）"
            } else {
                "正在直接抓取手机内部媒体声音（戴耳机也可用）"
            }
        )
        restartAudioPipeline(next)
    }

    private fun startTranslationConsumer() {
        translateJob?.cancel()
        translateJob = lifecycleScope.launch(Dispatchers.Default) {
            var lastTranslatedSource = ""
            var lastTranslateTimestamp = 0L

            transcriptFlow.collect { event ->
                val now = System.currentTimeMillis()
                // 流式中间结果做 180ms 节流防抖，避免每个词都触发翻译导致发热；句尾定稿立即翻译
                if (!event.isFinal && now - lastTranslateTimestamp < 180L) {
                    return@collect
                }
                if (event.text == lastTranslatedSource && !event.isFinal) {
                    return@collect
                }

                lastTranslatedSource = event.text
                lastTranslateTimestamp = now

                val zh = translatorEngine.translateToChinese(
                    text = event.text,
                    rawLangTag = event.lang
                )
                overlay?.updateSubtitles(
                    original = event.text,
                    translated = zh
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun restartAudioPipeline(source: AudioCaptureSource) {
        captureJob?.cancel()
        captureJob = lifecycleScope.launch(Dispatchers.IO) {
            overlay?.updateStatusHint("正在加载本地离线语音模型...", "首次加载约需 1-3 秒，完全离线运行")

            val asrSession = try {
                SherpaAsrSession(
                    preset = prefs.selectedPreset,
                    modelManager = modelManager,
                    languageHint = prefs.sourceLanguageHint,
                    onTranscript = { event ->
                        transcriptFlow.tryEmit(event)
                    }
                )
            } catch (e: Exception) {
                overlay?.updateStatusHint("模型加载失败", e.message ?: "请返回应用检查模型是否完整下载")
                return@launch
            }

            val audioRecord = try {
                createAudioRecord(source)
            } catch (e: Exception) {
                asrSession.release()
                overlay?.updateStatusHint("音频捕获初始化失败", e.message ?: "请检查录音与内录权限")
                return@launch
            }

            try {
                if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                    overlay?.updateStatusHint(
                        "音频通道未就绪",
                        "点击上方「🎧/🎤」切换音源或重启字幕服务"
                    )
                    return@launch
                }

                audioRecord.startRecording()
                overlay?.updateAudioSourceBadge(source)
                overlay?.updateStatusHint(
                    "灵译字幕已就绪（${source.label}）",
                    "切换到 YouTube / TikTok 播放视频即可实时出双语字幕"
                )

                // 每次读取 0.1 秒音频帧（1600 采样点 @ 16kHz）兼顾低延迟与低 CPU 唤醒开销
                val readChunkSamples = SherpaAsrSession.SAMPLE_RATE / 10
                val pcmBuffer = ShortArray(readChunkSamples)
                val floatBuffer = FloatArray(readChunkSamples)

                var digitalZeroSamplesCount = 0L
                var hintedSilenceRestriction = false

                while (isActive) {
                    if (isPaused) {
                        delay(150L)
                        continue
                    }

                    val read = audioRecord.read(pcmBuffer, 0, pcmBuffer.size)
                    if (read <= 0) {
                        delay(20L)
                        continue
                    }

                    var peak = 0
                    for (i in 0 until read) {
                        val s = pcmBuffer[i].toInt()
                        val absVal = abs(s)
                        if (absVal > peak) peak = absVal
                        floatBuffer[i] = s / 32768.0f
                    }

                    // 检测部分受保护视频流返回纯数字 0 的情况，人性化提示一键切麦克风
                    if (source == AudioCaptureSource.INTERNAL_AUDIO) {
                        if (peak == 0) {
                            digitalZeroSamplesCount += read
                            if (!hintedSilenceRestriction && digitalZeroSamplesCount > SherpaAsrSession.SAMPLE_RATE * 8L) {
                                hintedSilenceRestriction = true
                                overlay?.updateStatusHint(
                                    "当前暂无内录声音（若视频已在播放但无反应，可能该应用限制内录）",
                                    "可轻触字幕点「🎧 内录」切换为「🎤 麦克风」外放收音"
                                )
                            }
                        } else {
                            digitalZeroSamplesCount = 0L
                        }
                    }

                    asrSession.acceptSamples(floatBuffer, read)
                }
            } finally {
                runCatching {
                    if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        audioRecord.stop()
                    }
                }
                runCatching { audioRecord.release() }
                asrSession.release()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(source: AudioCaptureSource): AudioRecord {
        val sampleRate = SherpaAsrSession.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(sampleRate * 2)

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        return if (source == AudioCaptureSource.INTERNAL_AUDIO) {
            val mp = mediaProjection
                ?: throw IllegalStateException("系统内录会话未授权，请重新点击启动")

            val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBuf * 2)
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .build()
        } else {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(minBuf * 2)
                .build()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "用于保持 YouTube / TikTok 实时悬浮字幕后台稳定运行"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(isLocked: Boolean): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleLockIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, LiveSubtitleService::class.java).apply { action = ACTION_TOGGLE_LOCK },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, LiveSubtitleService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val lockLabel = if (isLocked) {
            getString(R.string.notif_action_unlock)
        } else {
            getString(R.string.notif_action_lock)
        }

        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notif_title_running))
            .setContentText(
                if (isLocked) "悬浮窗已开启防误触穿透 · 点击下方按钮可解锁"
                else "正在离线识别并翻译视频语音 · 点击返回设置"
            )
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .addAction(0, lockLabel, toggleLockIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            .build()
    }

    private fun stopSelfCleanly() {
        _runningState.value = false
        captureJob?.cancel()
        captureJob = null
        translateJob?.cancel()
        translateJob = null
        overlay?.destroy()
        overlay = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        _runningState.value = false
        captureJob?.cancel()
        translateJob?.cancel()
        overlay?.destroy()
        overlay = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        translatorEngine.closeAll()
        super.onDestroy()
    }
}
