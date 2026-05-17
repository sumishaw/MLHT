package com.example.nihongolens

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.*
import android.util.TypedValue
import android.view.*
import android.view.animation.AlphaAnimation
import android.widget.*
import androidx.core.app.NotificationCompat

/**
 * OverlayService
 *
 * Draws a full-width floating subtitle strip (TYPE_APPLICATION_OVERLAY) over
 * all other apps.  The strip is completely transparent — only bold white text
 * with a multi-layer black shadow is visible, so it sits cleanly over any video.
 *
 * Layout:
 *   - Starts at the LEFT edge of the screen, spans the full width to the RIGHT.
 *   - No background box, no border, no frame — plain text only.
 *   - Anchored near the bottom of the screen; user can drag it anywhere.
 *
 * Text buffering:
 *   - Up to 2 lines shown at once.
 *   - After 2 lines are accumulated the bar pauses for READ_PAUSE_MS.
 *   - After CLEAR_DELAY_MS of silence the overlay fades out.
 *
 * NOTE: updateText() now accepts (original, hindi) — the app always outputs
 * Hindi. The original/source-language string is kept for debugging only.
 */
class OverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "nihongo_overlay"
        const val NOTIF_ID   = 1

        @Volatile var latestOriginal = ""
        @Volatile var latestHindi    = ""

        @Volatile private var pushCallback: ((String, String) -> Unit)? = null

        /**
         * Push new subtitle text to the overlay.
         * @param original  Source-language text (for logging / Flutter UI)
         * @param hindi     Hindi translated text to display on screen
         */
        fun updateText(original: String, hindi: String) {
            latestOriginal = original
            latestHindi    = hindi
            pushCallback?.invoke(original, hindi)
        }
    }

    private var windowManager: WindowManager?              = null
    private var overlayView:   View?                       = null
    private var subtitleTv:    TextView?                   = null
    private var params:        WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var running   = true
    @Volatile private var viewAdded = false

    // 2-line read-pause buffer
    private val lineBuffer    = ArrayDeque<String>(4)
    private val pendingBuffer = ArrayDeque<String>(16)
    private val MAX_LINES     = 2
    private val READ_PAUSE_MS = 3_500L
    private val CLEAR_DELAY   = 12_000L
    @Volatile private var isPaused       = false
    private var pauseRunnable: Runnable? = null
    private var clearRunnable: Runnable? = null

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        mainHandler.post { if (running) buildOverlay() }

        pushCallback = { _, hindi ->
            mainHandler.post { onNewText(hindi) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running      = false
        pushCallback = null
        mainHandler.removeCallbacksAndMessages(null)
        if (viewAdded) {
            try { windowManager?.removeView(overlayView) } catch (_: Exception) {}
            viewAdded = false
        }
        super.onDestroy()
    }

    // ── Text handling ─────────────────────────────────────────────────────────

    private fun onNewText(hindi: String) {
        if (hindi.isBlank()) return

        val lines = hindi.lines().map { it.trim() }.filter { it.isNotEmpty() }

        if (isPaused) {
            lines.forEach { line ->
                if (pendingBuffer.lastOrNull() != line) pendingBuffer.addLast(line)
            }
            rescheduleClear()
            return
        }

        for (line in lines) {
            if (lineBuffer.lastOrNull() == line) continue
            lineBuffer.addLast(line)
            if (lineBuffer.size >= MAX_LINES) {
                showBuffer()
                startPause()
                return
            }
        }
        showBuffer()
        rescheduleClear()
    }

    private fun showBuffer() {
        val text = lineBuffer.joinToString("\n")
        subtitleTv?.apply {
            if (this.text.toString() != text) {
                this.text = text
                alpha = 1f
                clearAnimation()
                startAnimation(AlphaAnimation(0.3f, 1f).apply {
                    duration = 200; fillAfter = true
                })
            }
        }
    }

    private fun startPause() {
        isPaused = true
        pauseRunnable?.let { mainHandler.removeCallbacks(it) }
        pauseRunnable = Runnable {
            lineBuffer.clear()
            while (pendingBuffer.isNotEmpty() && lineBuffer.size < MAX_LINES)
                lineBuffer.addLast(pendingBuffer.removeFirst())
            isPaused = false
            if (lineBuffer.isNotEmpty()) {
                showBuffer()
                if (lineBuffer.size >= MAX_LINES) startPause() else rescheduleClear()
            } else {
                subtitleTv?.text = ""
                rescheduleClear()
            }
        }
        mainHandler.postDelayed(pauseRunnable!!, READ_PAUSE_MS)
    }

    private fun rescheduleClear() {
        clearRunnable?.let { mainHandler.removeCallbacks(it) }
        clearRunnable = Runnable {
            subtitleTv?.startAnimation(AlphaAnimation(1f, 0f).apply {
                duration = 2_000; fillAfter = true
            })
            mainHandler.postDelayed({
                lineBuffer.clear()
                pendingBuffer.clear()
                isPaused = false
                subtitleTv?.apply { clearAnimation(); alpha = 1f; text = "" }
            }, 2_100)
        }
        mainHandler.postDelayed(clearRunnable!!, CLEAR_DELAY)
    }

    // ── Overlay construction ──────────────────────────────────────────────────

    private fun buildOverlay() {
        try {
            // Root container: fully transparent, no background, no border.
            val container = FrameLayout(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
            }

            subtitleTv = TextView(this).apply {
                text     = ""
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setLineSpacing(0f, 1.2f)
                maxLines = MAX_LINES

                // Left-aligned — text starts at the left edge of the screen
                gravity = Gravity.START or Gravity.CENTER_VERTICAL

                // Multi-layer shadow for readability over any video background
                // radius=10 gives a soft halo; offset=(1,1) adds depth
                setShadowLayer(10f, 1f, 1f, Color.BLACK)
                setBackgroundColor(Color.TRANSPARENT)
                // Horizontal padding so text doesn't touch the very edge
                setPadding(dp(12), dp(4), dp(12), dp(4))
            }

            // Subtitle fills the full container width, anchored at the start (left)
            container.addView(
                subtitleTv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.START or Gravity.CENTER_VERTICAL
                )
            )

            overlayView = container

            val sw = resources.displayMetrics.widthPixels

            params = WindowManager.LayoutParams(
                sw,   // exact screen width — left to right
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                // Anchor at BOTTOM-LEFT; x=0 means flush with the left edge
                gravity = Gravity.BOTTOM or Gravity.START
                x = 0
                y = dp(80)   // above the navigation bar
            }

            // Draggable: user can reposition the subtitle bar anywhere on screen
            var startRawX = 0f; var startRawY = 0f
            var initX     = 0;  var initY     = 0
            container.setOnTouchListener { _, ev ->
                val p = params ?: return@setOnTouchListener false
                when (ev.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = ev.rawX; startRawY = ev.rawY
                        initX     = p.x;     initY     = p.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = initX + (ev.rawX - startRawX).toInt()
                        p.y = initY - (ev.rawY - startRawY).toInt()
                        if (viewAdded) try {
                            windowManager?.updateViewLayout(overlayView, p)
                        } catch (_: Exception) {}
                    }
                }
                true
            }

            windowManager?.addView(overlayView, params)
            viewAdded = true
        } catch (e: Exception) {
            android.util.Log.e("OverlayService", "buildOverlay error: ${e.message}")
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel(
                CHANNEL_ID,
                "Caption Lens Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
             .also { getSystemService(NotificationManager::class.java)
                         .createNotificationChannel(it) }
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Caption Lens Active")
            .setContentText("Hindi subtitle overlay running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setSilent(true)
            .build()
}
