package com.livesub.translator.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.mlkit.nl.translate.TranslateLanguage
import com.livesub.translator.R
import com.livesub.translator.config.AppPreferences
import com.livesub.translator.config.AsrModelPreset
import com.livesub.translator.config.AudioCaptureSource
import com.livesub.translator.databinding.ActivityMainBinding
import com.livesub.translator.engine.OfflineModelManager
import com.livesub.translator.engine.OfflineTranslatorEngine
import com.livesub.translator.service.LiveSubtitleService
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPreferences
    private lateinit var modelManager: OfflineModelManager
    private val translatorEngine = OfflineTranslatorEngine()

    private var asrDownloadJob: Job? = null

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshAllStates()
    }

    private val runtimePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshAllStates()
    }

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startLiveSubtitleService(result.resultCode, result.data)
        } else {
            Toast.makeText(this, "未授予系统内录权限，已取消启动", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)
        modelManager = OfflineModelManager(this)

        bindControls()
        observeServiceState()
        refreshAllStates()
    }

    override fun onResume() {
        super.onResume()
        refreshAllStates()
    }

    private fun bindControls() {
        // 1. 权限按钮
        binding.btnGrantOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            }
        }

        binding.btnGrantAudio.setOnClickListener {
            requestAudioAndNotificationPermissions()
        }

        // 2. 语音识别模型选择与下载
        binding.switchMirror.isChecked = prefs.useDownloadMirror
        binding.switchMirror.setOnCheckedChangeListener { _, checked ->
            prefs.useDownloadMirror = checked
        }

        when (prefs.selectedPreset) {
            AsrModelPreset.STREAMING_EN_ZIPFORMER -> binding.rbStreamingEn.isChecked = true
            AsrModelPreset.MULTILINGUAL_SENSEVOICE -> binding.rbSenseVoiceMulti.isChecked = true
        }
        updateSenseVoiceLangVisibility()

        binding.rgAsrPreset.setOnCheckedChangeListener { _, checkedId ->
            prefs.selectedPreset = if (checkedId == R.id.rbSenseVoiceMulti) {
                AsrModelPreset.MULTILINGUAL_SENSEVOICE
            } else {
                AsrModelPreset.STREAMING_EN_ZIPFORMER
            }
            updateSenseVoiceLangVisibility()
            refreshAsrModelCard()
            refreshReadinessBanner()
        }

        when (prefs.sourceLanguageHint) {
            "en" -> binding.chipLangEn.isChecked = true
            "ja" -> binding.chipLangJa.isChecked = true
            "ko" -> binding.chipLangKo.isChecked = true
            else -> binding.chipLangAuto.isChecked = true
        }
        binding.chipGroupLangHint.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull()
            prefs.sourceLanguageHint = when (id) {
                R.id.chipLangEn -> "en"
                R.id.chipLangJa -> "ja"
                R.id.chipLangKo -> "ko"
                else -> "auto"
            }
        }

        binding.btnDownloadAsrModel.setOnClickListener {
            if (asrDownloadJob?.isActive == true) {
                asrDownloadJob?.cancel()
                binding.progressAsrDownload.visibility = View.GONE
                binding.tvAsrModelState.text = "已取消下载"
                refreshAsrModelCard()
                return@setOnClickListener
            }
            startAsrModelDownload(prefs.selectedPreset)
        }

        binding.btnDeleteAsrModel.setOnClickListener {
            modelManager.deleteModel(prefs.selectedPreset)
            Toast.makeText(this, "已删除模型文件释放存储空间", Toast.LENGTH_SHORT).show()
            refreshAsrModelCard()
            refreshReadinessBanner()
        }

        // 3. 离线翻译词库下载按钮
        binding.btnDictEn.setOnClickListener {
            handleDictionaryAction(TranslateLanguage.ENGLISH, binding.btnDictEn, "英语")
        }
        binding.btnDictJa.setOnClickListener {
            handleDictionaryAction(TranslateLanguage.JAPANESE, binding.btnDictJa, "日语")
        }
        binding.btnDictKo.setOnClickListener {
            handleDictionaryAction(TranslateLanguage.KOREAN, binding.btnDictKo, "韩语")
        }

        // 4. 音源与悬浮窗外观
        when (prefs.audioSource) {
            AudioCaptureSource.INTERNAL_AUDIO -> binding.rbSourceInternal.isChecked = true
            AudioCaptureSource.MICROPHONE -> binding.rbSourceMic.isChecked = true
        }
        binding.rgAudioSource.setOnCheckedChangeListener { _, checkedId ->
            prefs.audioSource = if (checkedId == R.id.rbSourceMic) {
                AudioCaptureSource.MICROPHONE
            } else {
                AudioCaptureSource.INTERNAL_AUDIO
            }
        }

        binding.switchBilingual.isChecked = prefs.showBilingual
        binding.switchBilingual.setOnCheckedChangeListener { _, checked ->
            prefs.showBilingual = checked
            notifyOverlayStyleChanged()
        }

        val initSize = prefs.subtitleTextSizeSp.coerceIn(14, 26)
        binding.sliderFontSize.value = initSize.toFloat()
        binding.tvFontSizeLabel.text = "中文字幕字号：$initSize sp"
        binding.sliderFontSize.addOnChangeListener { _, value, _ ->
            val sp = value.toInt()
            prefs.subtitleTextSizeSp = sp
            binding.tvFontSizeLabel.text = "中文字幕字号：$sp sp"
            notifyOverlayStyleChanged()
        }

        val initAlpha = prefs.overlayAlpha.coerceIn(80, 240)
        binding.sliderAlpha.value = (initAlpha / 5 * 5).toFloat()
        binding.tvAlphaLabel.text = "字幕底框不透明度：${(initAlpha * 100) / 255}%"
        binding.sliderAlpha.addOnChangeListener { _, value, _ ->
            val alpha = value.toInt()
            prefs.overlayAlpha = alpha
            binding.tvAlphaLabel.text = "字幕底框不透明度：${(alpha * 100) / 255}%"
            notifyOverlayStyleChanged()
        }

        // 5. 主启动/停止按钮
        binding.btnToggleService.setOnClickListener {
            if (LiveSubtitleService.runningState.value) {
                stopLiveSubtitleService()
            } else {
                attemptLaunchSubtitleService()
            }
        }
    }

    private fun observeServiceState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                LiveSubtitleService.runningState.collect { running ->
                    if (running) {
                        binding.tvServiceStatus.text = "当前状态：正在运行实时悬浮字幕"
                        binding.btnToggleService.text = "停止全局悬浮字幕"
                        binding.btnToggleService.backgroundTintList = ColorStateList.valueOf(
                            ContextCompat.getColor(this@MainActivity, R.color.accent_red)
                        )
                    } else {
                        binding.tvServiceStatus.text = "当前状态：未启动"
                        binding.btnToggleService.text = "启动全局双语悬浮字幕"
                        binding.btnToggleService.backgroundTintList = ColorStateList.valueOf(
                            ContextCompat.getColor(this@MainActivity, R.color.primary)
                        )
                    }
                    refreshReadinessBanner()
                }
            }
        }
    }

    private fun attemptLaunchSubtitleService() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "请先开启「悬浮窗权限」以在油管/TikTok上方显示字幕", Toast.LENGTH_LONG).show()
            binding.btnGrantOverlay.performClick()
            return
        }

        if (!hasRecordAudioPermission()) {
            Toast.makeText(this, "请先授予音频录制权限（用于抓取系统内录声音）", Toast.LENGTH_LONG).show()
            requestAudioAndNotificationPermissions()
            return
        }

        val preset = prefs.selectedPreset
        if (!modelManager.isModelReady(preset)) {
            Toast.makeText(this, "请先在「第 2 步」点击下载离线语音识别模型", Toast.LENGTH_LONG).show()
            return
        }

        if (prefs.audioSource == AudioCaptureSource.INTERNAL_AUDIO) {
            val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(mpManager.createScreenCaptureIntent())
        } else {
            startLiveSubtitleService(Activity.RESULT_CANCELED, null)
        }
    }

    private fun startLiveSubtitleService(resultCode: Int, resultData: Intent?) {
        val intent = Intent(this, LiveSubtitleService::class.java).apply {
            action = LiveSubtitleService.ACTION_START
            putExtra(LiveSubtitleService.EXTRA_RESULT_CODE, resultCode)
            if (resultData != null) {
                putExtra(LiveSubtitleService.EXTRA_RESULT_DATA, resultData)
            }
        }
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "全局字幕已启动！现在可切去打开 YouTube 或 TikTok", Toast.LENGTH_LONG).show()
    }

    private fun stopLiveSubtitleService() {
        val intent = Intent(this, LiveSubtitleService::class.java).apply {
            action = LiveSubtitleService.ACTION_STOP
        }
        startService(intent)
    }

    private fun notifyOverlayStyleChanged() {
        if (LiveSubtitleService.runningState.value) {
            val intent = Intent(this, LiveSubtitleService::class.java).apply {
                action = LiveSubtitleService.ACTION_REFRESH_STYLE
            }
            startService(intent)
        }
    }

    private fun startAsrModelDownload(preset: AsrModelPreset) {
        asrDownloadJob = lifecycleScope.launch {
            binding.progressAsrDownload.visibility = View.VISIBLE
            binding.progressAsrDownload.progress = 0
            binding.btnDownloadAsrModel.text = "取消下载"
            binding.btnDeleteAsrModel.visibility = View.GONE

            try {
                modelManager.downloadAndExtractModel(
                    preset = preset,
                    useMirror = prefs.useDownloadMirror
                ) { stage, pct ->
                    runOnUiThread {
                        binding.tvAsrModelState.text = stage
                        binding.progressAsrDownload.progress = pct
                    }
                }
                Toast.makeText(this@MainActivity, "${preset.title} 下载解压完成！", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                binding.tvAsrModelState.text = "下载失败：${e.message}"
                Toast.makeText(
                    this@MainActivity,
                    "下载遇到问题，可尝试切换「国内镜像加速」开关后重试",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                binding.progressAsrDownload.visibility = View.GONE
                refreshAsrModelCard()
                refreshReadinessBanner()
            }
        }
    }

    private fun handleDictionaryAction(langCode: String, btn: MaterialButton, label: String) {
        btn.isEnabled = false
        btn.text = "下载中..."
        lifecycleScope.launch {
            try {
                val already = translatorEngine.isLanguageDownloaded(langCode)
                if (!already) {
                    translatorEngine.downloadLanguagePack(langCode)
                    Toast.makeText(this@MainActivity, "${label}离线翻译词库已就绪！", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "${label}词库已处于离线就绪状态", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "${label}词库下载失败: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                btn.isEnabled = true
                refreshDictionaryButtons()
                refreshReadinessBanner()
            }
        }
    }

    private fun refreshAllStates() {
        refreshPermissionRow()
        refreshAsrModelCard()
        refreshDictionaryButtons()
        refreshReadinessBanner()
    }

    private fun refreshPermissionRow() {
        val overlayOk = Settings.canDrawOverlays(this)
        binding.tvOverlayPermStatus.text = if (overlayOk) {
            "悬浮窗权限：已开启 ✓"
        } else {
            "悬浮窗权限：未开启（必须开启才能跨App显示）"
        }
        binding.btnGrantOverlay.isEnabled = !overlayOk
        binding.btnGrantOverlay.text = if (overlayOk) "已授权" else "去开启"

        val audioOk = hasRecordAudioPermission()
        binding.tvAudioPermStatus.text = if (audioOk) {
            "音频内录权限：已授权 ✓"
        } else {
            "音频内录权限：未授权"
        }
        binding.btnGrantAudio.isEnabled = !audioOk
        binding.btnGrantAudio.text = if (audioOk) "已授权" else "去授权"
    }

    private fun refreshAsrModelCard() {
        if (asrDownloadJob?.isActive == true) return
        val preset = prefs.selectedPreset
        val ready = modelManager.isModelReady(preset)
        if (ready) {
            binding.tvAsrModelState.text = "当前状态：已下载到本地，支持 100% 断网离线识别 ✓"
            binding.btnDownloadAsrModel.text = "已离线就绪 ✓"
            binding.btnDownloadAsrModel.isEnabled = false
            binding.btnDeleteAsrModel.visibility = View.VISIBLE
        } else {
            binding.tvAsrModelState.text = "当前状态：尚未下载（仅首次需联网下载一次）"
            binding.btnDownloadAsrModel.text = "一键下载所选语音模型"
            binding.btnDownloadAsrModel.isEnabled = true
            binding.btnDeleteAsrModel.visibility = View.GONE
        }
    }

    private fun refreshDictionaryButtons() {
        lifecycleScope.launch {
            updateSingleDictBtn(TranslateLanguage.ENGLISH, binding.btnDictEn)
            updateSingleDictBtn(TranslateLanguage.JAPANESE, binding.btnDictJa)
            updateSingleDictBtn(TranslateLanguage.KOREAN, binding.btnDictKo)
        }
    }

    private suspend fun updateSingleDictBtn(langCode: String, btn: MaterialButton) {
        val downloaded = translatorEngine.isLanguageDownloaded(langCode)
        btn.text = if (downloaded) "已就绪 ✓" else "下载"
        btn.isEnabled = !downloaded
    }

    private fun refreshReadinessBanner() {
        lifecycleScope.launch {
            if (LiveSubtitleService.runningState.value) {
                binding.tvReadinessSummary.text =
                    "已悬浮在屏幕上方！现在直接切换到 YouTube 或 TikTok 播放无字幕视频即可。"
                return@launch
            }
            val overlayOk = Settings.canDrawOverlays(this@MainActivity)
            val audioOk = hasRecordAudioPermission()
            val asrOk = modelManager.isModelReady(prefs.selectedPreset)
            val enDictOk = translatorEngine.isLanguageDownloaded(TranslateLanguage.ENGLISH)

            binding.tvReadinessSummary.text = when {
                !overlayOk || !audioOk -> "待完成：请先在下方「1. 手机权限授权」开启悬浮窗与内录权限"
                !asrOk -> "待完成：请在下方「2. 离线语音识别引擎」一键下载离线语音模型"
                !enDictOk -> "建议完成：请在「3. 离线翻译词库」点击下载英语（或日/韩）离线词库"
                else -> "全部离线组件已就绪！点击下方按钮即可启动并切去刷油管 / TikTok ✓"
            }
        }
    }

    private fun updateSenseVoiceLangVisibility() {
        binding.layoutSenseVoiceLang.visibility =
            if (prefs.selectedPreset == AsrModelPreset.MULTILINGUAL_SENSEVOICE) View.VISIBLE else View.GONE
    }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestAudioAndNotificationPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        runtimePermissionsLauncher.launch(perms.toTypedArray())
    }

    override fun onDestroy() {
        translatorEngine.closeAll()
        super.onDestroy()
    }
}
