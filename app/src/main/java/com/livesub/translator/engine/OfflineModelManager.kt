package com.livesub.translator.engine

import android.content.Context
import com.livesub.translator.config.AsrModelPreset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

data class ZipformerPaths(
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val tokens: String
)

data class SenseVoicePaths(
    val model: String,
    val tokens: String,
    val sileroVad: String
)

class OfflineModelManager(private val context: Context) {

    private val modelsRoot: File
        get() = File(context.filesDir, "asr_models").apply { mkdirs() }

    private val vadFile: File
        get() = File(modelsRoot, AsrModelPreset.SILERO_VAD_FILENAME)

    fun isModelReady(preset: AsrModelPreset): Boolean {
        return when (preset) {
            AsrModelPreset.STREAMING_EN_ZIPFORMER -> resolveZipformerPaths(preset) != null
            AsrModelPreset.MULTILINGUAL_SENSEVOICE -> resolveSenseVoicePaths(preset) != null
        }
    }

    fun resolveZipformerPaths(preset: AsrModelPreset = AsrModelPreset.STREAMING_EN_ZIPFORMER): ZipformerPaths? {
        val dir = File(modelsRoot, preset.folderName)
        if (!dir.exists() || !dir.isDirectory) return null
        val files = dir.listFiles().orEmpty()

        // Prefer int8 encoder & joiner for faster mobile CPU inference; prefer fp32 decoder per sherpa-onnx guidelines
        val encoder = files.firstOrNull { it.name.startsWith("encoder") && it.name.endsWith(".int8.onnx") }
            ?: files.firstOrNull { it.name.startsWith("encoder") && it.name.endsWith(".onnx") }
        val decoder = files.firstOrNull { it.name.startsWith("decoder") && it.name.endsWith(".onnx") && !it.name.endsWith(".int8.onnx") }
            ?: files.firstOrNull { it.name.startsWith("decoder") && it.name.endsWith(".onnx") }
        val joiner = files.firstOrNull { it.name.startsWith("joiner") && it.name.endsWith(".int8.onnx") }
            ?: files.firstOrNull { it.name.startsWith("joiner") && it.name.endsWith(".onnx") }
        val tokens = File(dir, "tokens.txt").takeIf { it.exists() && it.length() > 0 }

        return if (encoder != null && decoder != null && joiner != null && tokens != null) {
            ZipformerPaths(
                encoder = encoder.absolutePath,
                decoder = decoder.absolutePath,
                joiner = joiner.absolutePath,
                tokens = tokens.absolutePath
            )
        } else {
            null
        }
    }

    fun resolveSenseVoicePaths(preset: AsrModelPreset = AsrModelPreset.MULTILINGUAL_SENSEVOICE): SenseVoicePaths? {
        val dir = File(modelsRoot, preset.folderName)
        if (!dir.exists() || !dir.isDirectory) return null
        if (!vadFile.exists() || vadFile.length() < 100_000L) return null

        val files = dir.listFiles().orEmpty()
        val model = files.firstOrNull { it.name.endsWith(".int8.onnx") }
            ?: files.firstOrNull { it.name.endsWith(".onnx") }
        val tokens = File(dir, "tokens.txt").takeIf { it.exists() && it.length() > 0 }

        return if (model != null && tokens != null) {
            SenseVoicePaths(
                model = model.absolutePath,
                tokens = tokens.absolutePath,
                sileroVad = vadFile.absolutePath
            )
        } else {
            null
        }
    }

    suspend fun downloadAndExtractModel(
        preset: AsrModelPreset,
        useMirror: Boolean,
        onStatus: (stage: String, progressPercent: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (preset.needsSileroVad && (!vadFile.exists() || vadFile.length() < 100_000L)) {
            onStatus("正在下载 Silero VAD 语音检测模型...", 2)
            val tmpVad = File(modelsRoot, "silero_vad.onnx.tmp")
            downloadWithFallback(
                rawUrl = AsrModelPreset.SILERO_VAD_URL,
                targetFile = tmpVad,
                useMirror = useMirror
            ) { pct ->
                onStatus("正在下载 VAD 模型 ($pct%)...", (pct * 0.1f).toInt())
            }
            if (vadFile.exists()) vadFile.delete()
            tmpVad.renameTo(vadFile)
        }

        val archiveTmp = File(modelsRoot, "${preset.folderName}.tar.bz2.tmp")
        try {
            onStatus("正在连接服务器下载 ${preset.title}...", 10)
            downloadWithFallback(
                rawUrl = preset.archiveUrl,
                targetFile = archiveTmp,
                useMirror = useMirror
            ) { pct ->
                val mapped = 10 + (pct * 0.75f).toInt()
                onStatus("正在下载语音模型 ($pct%)...", mapped)
            }

            coroutineContext.ensureActive()
            onStatus("正在解压离线模型文件（约需 5-15 秒）...", 88)
            extractTarBz2(archiveTmp, modelsRoot)
            onStatus("模型准备就绪！", 100)
        } finally {
            if (archiveTmp.exists()) archiveTmp.delete()
        }

        if (!isModelReady(preset)) {
            throw IllegalStateException("模型解压完成但未找到完整的 ONNX 权重文件，请重试")
        }
    }

    fun deleteModel(preset: AsrModelPreset) {
        File(modelsRoot, preset.folderName).deleteRecursively()
    }

    private suspend fun downloadWithFallback(
        rawUrl: String,
        targetFile: File,
        useMirror: Boolean,
        onProgress: (Int) -> Unit
    ) {
        val urls = if (useMirror) {
            listOf(
                "https://ghfast.top/$rawUrl",
                "https://gh-proxy.com/$rawUrl",
                rawUrl
            )
        } else {
            listOf(
                rawUrl,
                "https://ghfast.top/$rawUrl"
            )
        }

        var lastError: Exception? = null
        for (candidate in urls) {
            try {
                downloadSingleUrl(candidate, targetFile, onProgress)
                if (targetFile.exists() && targetFile.length() > 50_000L) {
                    return
                }
            } catch (e: Exception) {
                coroutineContext.ensureActive()
                lastError = e
                if (targetFile.exists()) targetFile.delete()
            }
        }
        throw lastError ?: IllegalStateException("无法下载模型文件：$rawUrl")
    }

    private suspend fun downloadSingleUrl(
        urlStr: String,
        targetFile: File,
        onProgress: (Int) -> Unit
    ) {
        var currentUrl = urlStr
        var redirects = 0
        var conn: HttpURLConnection

        while (true) {
            coroutineContext.ensureActive()
            conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 45_000
                setRequestProperty("User-Agent", "LiveSubTranslate-Android/1.0")
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val loc = conn.getHeaderField("Location") ?: break
                currentUrl = URL(URL(currentUrl), loc).toString()
                conn.disconnect()
                redirects++
                if (redirects > 8) throw IllegalStateException("重定向次数过多")
            } else {
                break
            }
        }

        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IllegalStateException("HTTP ${conn.responseCode} (${conn.responseMessage})")
            }
            val totalBytes = conn.contentLengthLong
            var downloadedBytes = 0L
            var lastReportedPct = -1

            conn.inputStream.buffered(32 * 1024).use { input ->
                FileOutputStream(targetFile).buffered(32 * 1024).use { output ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        downloadedBytes += read
                        if (totalBytes > 0) {
                            val pct = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
                            if (pct != lastReportedPct) {
                                lastReportedPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun extractTarBz2(archiveFile: File, destDir: File) {
        val canonicalDestPath = destDir.canonicalPath + File.separator
        FileInputStream(archiveFile).use { fis ->
            BufferedInputStream(fis, 64 * 1024).use { bis ->
                BZip2CompressorInputStream(bis).use { bzis ->
                    TarArchiveInputStream(bzis).use { tis ->
                        var entry = tis.nextEntry
                        val buffer = ByteArray(32 * 1024)
                        while (entry != null) {
                            coroutineContext.ensureActive()
                            val outFile = File(destDir, entry.name)
                            // Guard against Zip-Slip path traversal
                            if (!outFile.canonicalPath.startsWith(canonicalDestPath)) {
                                throw SecurityException("Blocked unsafe tar entry: ${entry.name}")
                            }
                            if (entry.isDirectory) {
                                outFile.mkdirs()
                            } else {
                                outFile.parentFile?.mkdirs()
                                FileOutputStream(outFile).use { fos ->
                                    while (true) {
                                        val len = tis.read(buffer)
                                        if (len == -1) break
                                        fos.write(buffer, 0, len)
                                    }
                                }
                            }
                            entry = tis.nextEntry
                        }
                    }
                }
            }
        }
    }
}
