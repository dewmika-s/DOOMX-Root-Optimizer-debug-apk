package com.doomx.rootoptimizer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.util.Locale

/** Text-only overlay. Detection is restricted to Free Fire / Free Fire MAX foreground activity. */
class GameFpsOverlayService : Service() {
    companion object {
        const val ACTION_START = "com.doomx.rootoptimizer.action.FPS_START"
        const val ACTION_STOP = "com.doomx.rootoptimizer.action.FPS_STOP"
        private const val NOTIFICATION_ID = 7102
    }

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var fpsView: TextView? = null
    private var attached = false
    private val games = listOf("com.dts.freefireth", "com.dts.freefiremax")
    @Volatile private var scanRunning = false

    private val ticker = object : Runnable {
        override fun run() {
            if (scanRunning) return
            scanRunning = true
            Thread {
                try {
                    val activityDump = runShell("dumpsys activity activities")
                    val resumedLine = activityDump.lineSequence().firstOrNull { line ->
                        line.contains("topResumedActivity", true) ||
                            line.contains("mResumedActivity", true) ||
                            line.contains("ResumedActivity", true)
                    }.orEmpty()
                    val activePackage = games.firstOrNull { pkg ->
                        resumedLine.contains(pkg, ignoreCase = true)
                    }
                    val fps = if (activePackage != null) readFps(activePackage) else null
                    handler.post {
                        if (activePackage != null && Settings.canDrawOverlays(this@GameFpsOverlayService)) showOverlay(fps)
                        else hideOverlay()
                    }
                } finally {
                    scanRunning = false
                }
            }.start()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            handler.removeCallbacks(ticker)
            hideOverlay()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
        super.onDestroy()
    }

    private fun showOverlay(fps: Double?) {
        val label = if (fps != null && fps in 1.0..360.0) "DX  ${String.format(Locale.US, "%.0f", fps)} FPS" else "DX  FPS --"
        val view = fpsView ?: TextView(this).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = GradientDrawable().apply {
                setColor(0xDD08090C.toInt())
                cornerRadius = dp(7).toFloat()
                setStroke(dp(1), 0xFFFF3038.toInt())
            }
            elevation = dp(5).toFloat()
            fpsView = this
        }
        view.text = label
        if (!attached) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(10)
                y = dp(90)
            }
            try {
                windowManager?.addView(view, params)
                attached = true
            } catch (_: Exception) { attached = false }
        }
    }

    private fun hideOverlay() {
        if (attached) {
            try { fpsView?.let { windowManager?.removeView(it) } } catch (_: Exception) { }
            attached = false
        }
    }

    private fun readFps(packageName: String): Double? {
        val layers = runShell("dumpsys SurfaceFlinger --list")
        val layer = layers.lineSequence().firstOrNull { it.contains(packageName, ignoreCase = true) } ?: return null
        val safeLayer = layer.replace("'", "'\\''")
        val latency = runShell("dumpsys SurfaceFlinger --latency '$safeLayer'")
        val timestamps = latency.lineSequence().drop(1).mapNotNull { line ->
            line.trim().split(Regex("\\s+")).getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }
        }.distinct().sorted().takeLast(45)
        if (timestamps.size < 3) return null
        val seconds = (timestamps.last() - timestamps.first()).toDouble() / 1_000_000_000.0
        if (seconds <= 0.0) return null
        return ((timestamps.size - 1) / seconds).takeIf { it.isFinite() && it in 1.0..360.0 }
    }

    private fun runShell(command: String): String = try {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        output
    } catch (_: Exception) { "" }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("doomx_fps_overlay", "DOOMX FPS overlay", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, "doomx_fps_overlay") else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setContentTitle("DOOMX game FPS overlay")
            .setContentText("Small FPS text appears only while Free Fire / Free Fire MAX is foregrounded.")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
