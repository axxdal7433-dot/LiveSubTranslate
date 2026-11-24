package com.livesub.translator.engine

import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

/**
 * 基于 Google ML Kit 的纯本地离线翻译器。
 * 支持英->中、日->中、韩->中多语种自动路由与高频短语缓存。
 */
class OfflineTranslatorEngine {

    private val translators = ConcurrentHashMap<String, Translator>()
    private val cache = LruCache<String, String>(256)
    private val remoteModelManager = RemoteModelManager.getInstance()

    companion object {
        val SUPPORTED_SOURCE_LANGS = listOf(
            TranslateLanguage.ENGLISH to "英语 (English)",
            TranslateLanguage.JAPANESE to "日语 (日本語)",
            TranslateLanguage.KOREAN to "韩语 (한국어)"
        )
    }

    suspend fun isLanguageDownloaded(mlKitLangCode: String): Boolean {
        val model = TranslateRemoteModel.Builder(mlKitLangCode).build()
        val zhModel = TranslateRemoteModel.Builder(TranslateLanguage.CHINESE).build()
        return try {
            remoteModelManager.isModelDownloaded(model).await() &&
                remoteModelManager.isModelDownloaded(zhModel).await()
        } catch (_: Exception) {
            false
        }
    }

    suspend fun downloadLanguagePack(mlKitLangCode: String) {
        val translator = getOrCreateTranslator(mlKitLangCode)
        // 允许移动数据或 Wi-Fi 下载离线词库（约 30MB）
        val conditions = DownloadConditions.Builder().build()
        translator.downloadModelIfNeeded(conditions).await()
    }

    suspend fun deleteLanguagePack(mlKitLangCode: String) {
        translators.remove(mlKitLangCode)?.close()
        val model = TranslateRemoteModel.Builder(mlKitLangCode).build()
        remoteModelManager.deleteDownloadedModel(model).await()
    }

    /**
     * 将识别出的文本离线翻译为简体中文。
     * @param rawLangTag Sherpa-ONNX / SenseVoice 返回的语言标签（如 "en", "<|en|>", "ja", "ko", "zh"）或手动指定的语种
     */
    suspend fun translateToChinese(text: String, rawLangTag: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""

        val normalizedLang = normalizeLanguageCode(rawLangTag, trimmed)
        // 如果本身已经是中文或粤语汉字，无需再次翻译
        if (normalizedLang == TranslateLanguage.CHINESE) {
            return trimmed
        }

        val cacheKey = "$normalizedLang|$trimmed"
        cache.get(cacheKey)?.let { return it }

        return try {
            val translator = getOrCreateTranslator(normalizedLang)
            // 如果词库已下载，这步完全在本地秒级执行；若未下载则不阻塞死等
            val result = translator.translate(trimmed).await()
            cache.put(cacheKey, result)
            result
        } catch (_: Exception) {
            "（请先在首页下载 [${langDisplayName(normalizedLang)}] 离线翻译词库）"
        }
    }

    private fun getOrCreateTranslator(sourceLang: String): Translator {
        return translators.getOrPut(sourceLang) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(sourceLang)
                .setTargetLanguage(TranslateLanguage.CHINESE)
                .build()
            Translation.getClient(options)
        }
    }

    private fun normalizeLanguageCode(rawTag: String, sampleText: String): String {
        val clean = rawTag.lowercase()
            .replace("<|", "")
            .replace("|>", "")
            .trim()

        return when {
            clean.startsWith("zh") || clean.startsWith("yue") -> TranslateLanguage.CHINESE
            clean.startsWith("ja") || clean.startsWith("jp") -> TranslateLanguage.JAPANESE
            clean.startsWith("ko") || clean.startsWith("kr") -> TranslateLanguage.KOREAN
            clean.startsWith("en") -> TranslateLanguage.ENGLISH
            else -> detectScriptFallback(sampleText)
        }
    }

    /** 当标签为空或 auto 时，根据 Unicode 字符集快速兜底判断日/韩/中/英 */
    private fun detectScriptFallback(text: String): String {
        var hasKana = false
        var hasHangul = false
        var hasCjk = false
        for (ch in text) {
            val code = ch.code
            when {
                code in 0x3040..0x30FF || code in 0x31F0..0x31FF -> hasKana = true
                code in 0xAC00..0xD7AF || code in 0x1100..0x11FF -> hasHangul = true
                code in 0x4E00..0x9FFF -> hasCjk = true
            }
        }
        return when {
            hasKana -> TranslateLanguage.JAPANESE
            hasHangul -> TranslateLanguage.KOREAN
            hasCjk -> TranslateLanguage.CHINESE
            else -> TranslateLanguage.ENGLISH
        }
    }

    private fun langDisplayName(code: String): String = when (code) {
        TranslateLanguage.JAPANESE -> "日语"
        TranslateLanguage.KOREAN -> "韩语"
        TranslateLanguage.ENGLISH -> "英语"
        else -> code
    }

    fun closeAll() {
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
    }
}
