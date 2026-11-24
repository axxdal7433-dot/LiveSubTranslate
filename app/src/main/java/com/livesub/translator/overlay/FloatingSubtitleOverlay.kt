package com.livesub.translator.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.livesub.translator.config.AppPreferences
import com.livesub.translator.config.AudioCaptureSource
import com.livesub.translator.databinding.OverlayFloatingSubtitleBinding
import kotlin.math.abs
import kotlin.math.min

class FloatingSubtitleOverlay(
    private val context: Context,
    private val prefs: AppPreferences,
    private val onSwitchAudioSource: () -> Unit,
    private val onTogglePause: () -> Boolean,
    private val onLockStateChanged: (Boolean) -> Unit,
    private val onRequestStop: () -> Unit
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var binding: OverlayFloatingSubtitleBinding? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isAdded = false
    private var isTouchLocked = false

    private val autoHideToolbarRunnable = Runnable {
        binding?.toolbarScroll?.visibility = View.GONE
    }

    fun show() {
        if (isAdded) return
        val inflater = LayoutInflater.from(context)
        val b = OverlayFloatingSubtitleBinding.inflate(inflater)
        binding = b

        val metrics = context.resources.displayMetrics
        val maxPx = dpToPx(500)
        val targetWidth = min((metrics.widthPixels * 0.92f).toInt(), maxPx)
        val defaultY = (metrics.heightPixels * 0.68f).toInt()
        val savedY = prefs.overlayPosY.takeIf { it in 40..(metrics.heightPixels - 120) } ?: defaultY

        val params = WindowManager.LayoutParams(
            targetWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = savedY
        }
        layoutParams = params

        applyVisualPreferences()
        setupInteractions(b)

        windowManager.addView(b.root, params)
        isAdded = true
        scheduleToolbarAutoHide()
    }

    fun onConfigurationChanged() {
        val b = binding ?: return
        val params = layoutParams ?: return
        if (!isAdded) return

        val metrics = context.resources.displayMetrics
        val maxPx = dpToPx(500)
        params.width = min((metrics.widthPixels * 0.92f).toInt(), maxPx)
        params.y = params.y.coerceIn(30, (metrics.heightPixels - dpToPx(110)).coerceAtLeast(40))
        runCatching { windowManager.updateViewLayout(b.root, params) }
    }

    fun updateSubtitles(original: String, translated: String) {
        mainHandler.post {
            val b = binding ?: return@post
            if (prefs.showBilingual && original.isNotBlank() && original.trim() != translated.trim()) {
                b.tvOriginal.visibility = View.VISIBLE
                b.tvOriginal.text = original
            } else {
                b.tvOriginal.visibility = View.GONE
            }
            b.tvTranslated.text = translated
        }
    }

    fun updateStatusHint(title: String, detail: String) {
        mainHandler.post {
            val b = binding ?: return@post
            b.tvOriginal.visibility = if (title.isNotBlank()) View.VISIBLE else View.GONE
            b.tvOriginal.text = title
            b.tvTranslated.text = detail
        }
    }

    fun updateAudioSourceBadge(source: AudioCaptureSource) {
        mainHandler.post {
            binding?.btnSwitchSource?.text = when (source) {
                AudioCaptureSource.INTERNAL_AUDIO -> "🎧 内录"
                AudioCaptureSource.MICROPHONE -> "🎤 麦克风"
            }
        }
    }

    fun setTouchLocked(locked: Boolean) {
        mainHandler.post {
            val b = binding ?: return@post
            val params = layoutParams ?: return@post
            if (!isAdded) return@post

            isTouchLocked = locked
            params.flags = if (locked) {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            } else {
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }
            if (locked) {
                b.toolbarScroll.visibility = View.GONE
            } else {
                b.toolbarScroll.visibility = View.VISIBLE
                scheduleToolbarAutoHide()
            }
            runCatching { windowManager.updateViewLayout(b.root, params) }
            onLockStateChanged(locked)
        }
    }

    fun isLocked(): Boolean = isTouchLocked

    fun applyVisualPreferences() {
        mainHandler.post {
            val b = binding ?: return@post
            val zhSp = prefs.subtitleTextSizeSp.toFloat()
            val origSp = (zhSp - 4f).coerceAtLeast(11f)
            b.tvTranslated.setTextSize(TypedValue.COMPLEX_UNIT_SP, zhSp)
            b.tvOriginal.setTextSize(TypedValue.COMPLEX_UNIT_SP, origSp)
            b.tvOriginal.visibility = if (prefs.showBilingual) View.VISIBLE else View.GONE
            b.btnToggleBilingual.text = if (prefs.showBilingual) "双语" else "仅译文"
            updateAudioSourceBadge(prefs.audioSource)

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(16).toFloat()
                val alpha = prefs.overlayAlpha.coerceIn(70, 245)
                val color = (alpha shl 24) or 0x0F172A
                setColor(color)
                setStroke(dpToPx(1), 0x33FFFFFF)
            }
            b.overlayRoot.background = bg
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupInteractions(b: OverlayFloatingSubtitleBinding) {
        var initialX = 0
        var initialY = 0
        var touchDownRawX = 0f
        var touchDownRawY = 0f
        var moved = false
        val touchSlop = dpToPx(8)

        val dragTouchListener = View.OnTouchListener { _, event ->
            val params = layoutParams ?: return@OnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchDownRawX = event.rawX
                    touchDownRawY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchDownRawX).toInt()
                    val dy = (event.rawY - touchDownRawY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        moved = true
                    }
                    if (moved) {
                        val metrics = context.resources.displayMetrics
                        params.x = initialX + dx
                        params.y = (initialY + dy).coerceIn(24, metrics.heightPixels - dpToPx(90))
                        runCatching { windowManager.updateViewLayout(b.root, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        prefs.overlayPosY = params.y
                    } else {
                        toggleToolbar()
                    }
                    true
                }
                else -> false
            }
        }

        b.overlayRoot.setOnTouchListener(dragTouchListener)
        b.tvOriginal.setOnTouchListener(dragTouchListener)
        b.tvTranslated.setOnTouchListener(dragTouchListener)

        b.btnSwitchSource.setOnClickListener {
            scheduleToolbarAutoHide()
            onSwitchAudioSource()
        }

        b.btnToggleBilingual.setOnClickListener {
            prefs.showBilingual = !prefs.showBilingual
            applyVisualPreferences()
            scheduleToolbarAutoHide()
        }

        b.btnFontMinus.setOnClickListener {
            prefs.subtitleTextSizeSp -= 2
            applyVisualPreferences()
            scheduleToolbarAutoHide()
        }

        b.btnFontPlus.setOnClickListener {
            prefs.subtitleTextSizeSp += 2
            applyVisualPreferences()
            scheduleToolbarAutoHide()
        }

        b.btnPauseResume.setOnClickListener {
            val paused = onTogglePause()
            b.btnPauseResume.text = if (paused) "▶ 继续" else "⏸ 暂停"
            scheduleToolbarAutoHide()
        }

        b.btnLockTouch.setOnClickListener {
            setTouchLocked(true)
            Toast.makeText(context, "字幕已开启防误触穿透，下拉通知栏可一键解锁", Toast.LENGTH_SHORT).show()
        }

        b.btnCloseOverlay.setOnClickListener {
            onRequestStop()
        }
    }

    private fun toggleToolbar() {
        val b = binding ?: return
        if (b.toolbarScroll.visibility == View.VISIBLE) {
            mainHandler.removeCallbacks(autoHideToolbarRunnable)
            b.toolbarScroll.visibility = View.GONE
        } else {
            b.toolbarScroll.visibility = View.VISIBLE
            scheduleToolbarAutoHide()
        }
    }

    private fun scheduleToolbarAutoHide() {
        mainHandler.removeCallbacks(autoHideToolbarRunnable)
        mainHandler.postDelayed(autoHideToolbarRunnable, 4500L)
    }

    fun destroy() {
        mainHandler.removeCallbacks(autoHideToolbarRunnable)
        val b = binding
        if (isAdded && b != null) {
            runCatching { windowManager.removeView(b.root) }
        }
        isAdded = false
        binding = null
        layoutParams = null
    }

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
}
