package com.livesub.translator.config

import android.content.Context
import android.content.SharedPreferences

enum class AsrModelPreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val archiveUrl: String,
    val folderName: String,
    val needsSileroVad: Boolean,
    val defaultSourceLang: String
) {
    STREAMING_EN_ZIPFORMER(
        id = "streaming_en",
        title = "英语极速流式模型 (Zipformer 20M)",
        subtitle = "约 38MB · 边说边出词零延迟 · 最适合英文 YouTube 长视频/播客",
        archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2",
        folderName = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17",
        needsSileroVad = false,
        defaultSourceLang = "en"
    ),
    MULTILINGUAL_SENSEVOICE(
        id = "sensevoice_multi",
        title = "多语种全能模型 (SenseVoice Int8 + VAD)",
        subtitle = "约 165MB · 自动识别英/日/韩/粤/中 · 最适合混刷 TikTok 短视频",
        archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
        folderName = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
        needsSileroVad = true,
        defaultSourceLang = "auto"
    );

    companion object {
        const val SILERO_VAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"
        const val SILERO_VAD_FILENAME = "silero_vad.onnx"

        fun fromId(id: String): AsrModelPreset =
            entries.firstOrNull { it.id == id } ?: STREAMING_EN_ZIPFORMER
    }
}

enum class AudioCaptureSource(val id: String, val label: String) {
    INTERNAL_AUDIO("internal", "系统内录"),
    MICROPHONE("mic", "麦克风收音");

    companion object {
        fun fromId(id: String): AudioCaptureSource =
            entries.firstOrNull { it.id == id } ?: INTERNAL_AUDIO
    }
}

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("livesub_prefs", Context.MODE_PRIVATE)

    var selectedPreset: AsrModelPreset
        get() = AsrModelPreset.fromId(
            prefs.getString("selected_preset", AsrModelPreset.STREAMING_EN_ZIPFORMER.id).orEmpty()
        )
        set(value) = prefs.edit().putString("selected_preset", value.id).apply()

    var audioSource: AudioCaptureSource
        get() = AudioCaptureSource.fromId(
            prefs.getString("audio_source", AudioCaptureSource.INTERNAL_AUDIO.id).orEmpty()
        )
        set(value) = prefs.edit().putString("audio_source", value.id).apply()

    /** SenseVoice 手动固定语种或自动检测: "auto", "en", "ja", "ko" */
    var sourceLanguageHint: String
        get() = prefs.getString("source_lang_hint", "auto") ?: "auto"
        set(value) = prefs.edit().putString("source_lang_hint", value).apply()

    /** 是否同时显示双语字幕（false 则只显示中文译文） */
    var showBilingual: Boolean
        get() = prefs.getBoolean("show_bilingual", true)
        set(value) = prefs.edit().putBoolean("show_bilingual", value).apply()

    /** 译文字号 sp (14..26) */
    var subtitleTextSizeSp: Int
        get() = prefs.getInt("subtitle_size_sp", 18)
        set(value) = prefs.edit().putInt("subtitle_size_sp", value.coerceIn(13, 28)).apply()

    /** 悬浮窗背景不透明度 (80..240) */
    var overlayAlpha: Int
        get() = prefs.getInt("overlay_alpha", 195)
        set(value) = prefs.edit().putInt("overlay_alpha", value.coerceIn(70, 245)).apply()

    /** 国内网络 GitHub 下载镜像加速开关 */
    var useDownloadMirror: Boolean
        get() = prefs.getBoolean("use_download_mirror", true)
        set(value) = prefs.edit().putBoolean("use_download_mirror", value).apply()

    /** 悬浮窗上次记忆的 Y 坐标 */
    var overlayPosY: Int
        get() = prefs.getInt("overlay_pos_y", -1)
        set(value) = prefs.edit().putInt("overlay_pos_y", value).apply()
}
