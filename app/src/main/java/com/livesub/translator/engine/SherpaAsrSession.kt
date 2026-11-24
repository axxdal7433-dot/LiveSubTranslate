package com.livesub.translator.engine

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.livesub.translator.config.AsrModelPreset
import kotlin.math.sqrt

data class TranscriptEvent(
    val text: String,
    val lang: String,
    val isFinal: Boolean
)

/**
 * 封装 Sherpa-ONNX 两种离线实时识别管线：
 * 1) Streaming Zipformer (OnlineRecognizer)：边说边出词，极低延迟
 * 2) SenseVoice + Silero VAD (OfflineRecognizer + Vad)：自动识别英/日/韩/粤/中，带说话中动态预览与句尾精准定稿
 */
class SherpaAsrSession(
    private val preset: AsrModelPreset,
    private val modelManager: OfflineModelManager,
    private val languageHint: String,
    private val onTranscript: (TranscriptEvent) -> Unit
) {
    companion object {
        const val SAMPLE_RATE = 16000
        private const val VAD_WINDOW_SIZE = 512
        // 说话过程中每隔约 1.2 秒触发一次实时预览推理（19200 采样点 @ 16kHz）
        private const val PREVIEW_INTERVAL_SAMPLES = 19200
        private const val MAX_PREVIEW_BUFFER_SAMPLES = SAMPLE_RATE * 9
    }

    private var onlineRecognizer: OnlineRecognizer? = null
    private var onlineStream: OnlineStream? = null
    private var lastOnlineText: String = ""

    private var offlineRecognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private val vadRemainder = FloatArray(VAD_WINDOW_SIZE)
    private var vadRemainderCount = 0

    // 用于 SenseVoice 在长句未结束前提供流式预览体验的环形缓冲
    private var liveSpeechBuffer = FloatArray(MAX_PREVIEW_BUFFER_SAMPLES)
    private var liveSpeechLen = 0
    private var samplesSinceLastPreview = 0

    init {
        when (preset) {
            AsrModelPreset.STREAMING_EN_ZIPFORMER -> initStreamingZipformer()
            AsrModelPreset.MULTILINGUAL_SENSEVOICE -> initSenseVoiceWithVad()
        }
    }

    private fun initStreamingZipformer() {
        val paths = modelManager.resolveZipformerPaths(preset)
            ?: throw IllegalStateException("未找到英语极速流式模型文件，请先在首页下载")

        val transducer = OnlineTransducerModelConfig().apply {
            encoder = paths.encoder
            decoder = paths.decoder
            joiner = paths.joiner
        }

        val modelConfig = OnlineModelConfig().apply {
            this.transducer = transducer
            tokens = paths.tokens
            numThreads = 2
            provider = "cpu"
            debug = false
        }

        // 针对短视频/快语速博主优化断句：停顿 1.0 秒或连续说满 12 秒自动断句刷新字幕框
        val endpointConfig = EndpointConfig().apply {
            rule1 = EndpointRule(false, 2.0f, 0.0f)
            rule2 = EndpointRule(true, 1.0f, 0.0f)
            rule3 = EndpointRule(false, 0.0f, 12.0f)
        }

        val recConfig = OnlineRecognizerConfig().apply {
            featConfig = FeatureConfig().apply {
                sampleRate = SAMPLE_RATE
                featureDim = 80
            }
            this.modelConfig = modelConfig
            this.endpointConfig = endpointConfig
            enableEndpoint = true
            decodingMethod = "greedy_search"
        }

        val rec = OnlineRecognizer(assetManager = null, config = recConfig)
        onlineRecognizer = rec
        onlineStream = rec.createStream()
    }

    private fun initSenseVoiceWithVad() {
        val paths = modelManager.resolveSenseVoicePaths(preset)
            ?: throw IllegalStateException("未找到 SenseVoice 多语种模型或 VAD 文件，请先在首页下载")

        val senseVoiceCfg = OfflineSenseVoiceModelConfig().apply {
            model = paths.model
            language = if (languageHint == "auto") "" else languageHint
        }

        val offlineModelCfg = OfflineModelConfig().apply {
            senseVoice = senseVoiceCfg
            tokens = paths.tokens
            numThreads = 3
            provider = "cpu"
            debug = false
        }

        val offlineRecCfg = OfflineRecognizerConfig().apply {
            featConfig = FeatureConfig().apply {
                sampleRate = SAMPLE_RATE
                featureDim = 80
            }
            modelConfig = offlineModelCfg
            decodingMethod = "greedy_search"
        }

        offlineRecognizer = OfflineRecognizer(assetManager = null, config = offlineRecCfg)

        val sileroCfg = SileroVadModelConfig().apply {
            model = paths.sileroVad
            threshold = 0.45f
            minSilenceDuration = 0.35f
            minSpeechDuration = 0.20f
            windowSize = VAD_WINDOW_SIZE
            maxSpeechDuration = 8.5f
        }

        val vadCfg = VadModelConfig().apply {
            sileroVadModelConfig = sileroCfg
            sampleRate = SAMPLE_RATE
            numThreads = 1
            provider = "cpu"
            debug = false
        }

        vad = Vad(assetManager = null, config = vadCfg)
    }

    /**
     * 输入 16kHz 单声道归一化 [-1.0f, 1.0f] 音频帧（必须在单一工作线程串行调用）
     */
    fun acceptSamples(samples: FloatArray, length: Int = samples.size) {
        if (length <= 0) return
        val chunk = if (length == samples.size) samples else samples.copyOf(length)

        when (preset) {
            AsrModelPreset.STREAMING_EN_ZIPFORMER -> processOnlineChunk(chunk)
            AsrModelPreset.MULTILINGUAL_SENSEVOICE -> processSenseVoiceChunk(chunk)
        }
    }

    private fun processOnlineChunk(chunk: FloatArray) {
        val rec = onlineRecognizer ?: return
        val stream = onlineStream ?: return

        stream.acceptWaveform(chunk, SAMPLE_RATE)
        while (rec.isReady(stream)) {
            rec.decode(stream)
        }

        val text = rec.getResult(stream).text.trim()
        val isEndpoint = rec.isEndpoint(stream)

        if (text.isNotEmpty() && (text != lastOnlineText || isEndpoint)) {
            lastOnlineText = text
            onTranscript(
                TranscriptEvent(
                    text = formatEnglishSentence(text),
                    lang = "en",
                    isFinal = isEndpoint
                )
            )
        }

        if (isEndpoint) {
            rec.reset(stream)
            lastOnlineText = ""
        }
    }

    private fun processSenseVoiceChunk(chunk: FloatArray) {
        val detector = vad ?: return
        val rec = offlineRecognizer ?: return

        // 能量门限监测用于维护说话中实时预览缓冲
        val rms = computeRms(chunk)
        if (rms > 0.012f) {
            appendLiveBuffer(chunk)
            samplesSinceLastPreview += chunk.size
        }

        // 按 512 采样窗口送入 Silero VAD
        var offset = 0
        while (offset < chunk.size) {
            val space = VAD_WINDOW_SIZE - vadRemainderCount
            val toCopy = minOf(space, chunk.size - offset)
            System.arraycopy(chunk, offset, vadRemainder, vadRemainderCount, toCopy)
            vadRemainderCount += toCopy
            offset += toCopy

            if (vadRemainderCount == VAD_WINDOW_SIZE) {
                detector.acceptWaveform(vadRemainder.copyOf())
                vadRemainderCount = 0
            }
        }

        // 若 VAD 已切出完整句子，立即做精准定稿识别并清空预览缓冲
        var emittedFinal = false
        while (!detector.empty()) {
            val segment = detector.front()
            detector.pop()
            liveSpeechLen = 0
            samplesSinceLastPreview = 0

            if (segment.samples.size >= SAMPLE_RATE / 4) {
                decodeSenseVoice(rec, segment.samples, isFinal = true)
                emittedFinal = true
            }
        }

        // 若正在连续说话尚未断句，每隔约 1.2 秒输出一次实时预览，避免看视频时长时间空白等待
        if (!emittedFinal && liveSpeechLen >= SAMPLE_RATE && samplesSinceLastPreview >= PREVIEW_INTERVAL_SAMPLES) {
            samplesSinceLastPreview = 0
            val previewSamples = liveSpeechBuffer.copyOf(liveSpeechLen)
            decodeSenseVoice(rec, previewSamples, isFinal = false)
        }
    }

    private fun decodeSenseVoice(rec: OfflineRecognizer, samples: FloatArray, isFinal: Boolean) {
        val stream = rec.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            val result = rec.getResult(stream)
            val cleanedText = cleanSenseVoiceText(result.text)
            if (cleanedText.isNotEmpty()) {
                val lang = result.lang.ifBlank { languageHint }
                onTranscript(
                    TranscriptEvent(
                        text = cleanedText,
                        lang = lang,
                        isFinal = isFinal
                    )
                )
            }
        } finally {
            stream.release()
        }
    }

    private fun appendLiveBuffer(chunk: FloatArray) {
        if (liveSpeechLen + chunk.size > liveSpeechBuffer.size) {
            // 保留最近 5 秒上下文窗口
            val keep = SAMPLE_RATE * 5
            if (liveSpeechLen > keep) {
                System.arraycopy(liveSpeechBuffer, liveSpeechLen - keep, liveSpeechBuffer, 0, keep)
                liveSpeechLen = keep
            } else {
                liveSpeechLen = 0
            }
        }
        val copyLen = minOf(chunk.size, liveSpeechBuffer.size - liveSpeechLen)
        System.arraycopy(chunk, 0, liveSpeechBuffer, liveSpeechLen, copyLen)
        liveSpeechLen += copyLen
    }

    private fun computeRms(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sumSq = 0.0
        for (s in samples) {
            sumSq += (s * s)
        }
        return sqrt(sumSq / samples.size).toFloat()
    }

    private fun cleanSenseVoiceText(raw: String): String {
        // 过滤 SenseVoice 可能附带的 <|en|><|NEUTRAL|><|Speech|> 等特殊标记
        return raw.replace(Regex("<\\|[^>]+\\|>"), "").trim()
    }

    private fun formatEnglishSentence(raw: String): String {
        val lower = raw.lowercase().trim()
        return lower.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    fun release() {
        runCatching { onlineStream?.release() }
        onlineStream = null
        runCatching { onlineRecognizer?.release() }
        onlineRecognizer = null

        runCatching { vad?.release() }
        vad = null
        runCatching { offlineRecognizer?.release() }
        offlineRecognizer = null
    }
}
