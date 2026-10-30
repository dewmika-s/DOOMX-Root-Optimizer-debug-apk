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

/** Small text-only FPS overlay. It is shown only while a Free Fire package is resumed. */
class GameFpsOverlayService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var fpsView: TextView? = null
    private var attached = false
    private val games = listOf("com.dts.freefireth", "com.dts.freefiremax")

    private val ticker = object : Runnable {
        override fun run() {
            Thread {
                val top = runShell("dumpsys activity activities")
                val activePackage = games.firstOrNull { pkg ->
                    top.contains(pkg) && (top.contains("mResumedActivity") || top.contains("topResumedActivity") || top.contains("Resumed:"))
                }
                val fps = if (activePackage != null) readFps(activePackage) else null
                handler.post {
                    if (activePackage != null && Settings.canDrawOverlays(this@GameFpsOverlayService)) {
                        showOverlay(fps)
                    } else {
                        hideOverlay()
                    }
                }
            }.start()
            handler.postDelayed(this, 1200L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(7102, buildNotification())
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        handler.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
        super.onDestroy()
    }

    private fun showOverlay(fps: Double?) {
        val label = if (fps != null && fps in 1.0..360.0) "DX  ~${String.format(Locale.US, "%.0f", fps)} FPS" else "DX  FPS --"
        val view = fpsView ?: TextView(this).apply {
            textSize = 11f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("monospace", Typeface.BOLD)
            setPadding(dp(8), dp(5), dp(8), dp(5))
            background = GradientDrawable().apply {
                setColor(0x9908090C.toInt())
                cornerRadius = dp(7).toFloat()
                setStroke(dp(1), 0xAAFF3038.toInt())
            }
            elevation = dp(5).toFloat()
            fpsView = this
        }
        view.text = label
        if (!attached) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
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
            } catch (_: Exception) {
                attached = false
            }
        }
    }

    private fun hideOverlay() {
        if (attached) {
            try { windowManager?.removeView(fpsView) } catch (_: Exception) { }
            attached = false
        }
    }

    private fun readFps(packageName: String): Double? {
        val layers = runShell("dumpsys SurfaceFlinger --list")
        val layer = layers.lineSequence().firstOrNull { it.contains(packageName, ignoreCase = true) } ?: return null
        val safeLayer = layer.replace("'", "'\\''")
        val latency = runShell("dumpsys SurfaceFlinger --latency '$safeLayer'")
        val times = latency.lineSequence().drop(1).mapNotNull { line ->
            val f = line.trim().split(Regex("\\s+"))
            f.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }
        }.toList().distinct().sorted().takeLast(45)
        if (times.size < 3) return null
        val span = (times.last() - times.first()).toDouble() / 1_000_000_000.0
        if (span <= 0.0) return null
        val result = (times.size - 1) / span
        return result.takeIf { it.isFinite() && it in 1.0..360.0 }
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
            .setContentText("FPS text appears only while Free Fire / Free Fire MAX is in the foreground.")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
