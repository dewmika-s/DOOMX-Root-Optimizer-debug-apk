package com.doomx.rootoptimizer

import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : Activity() {
    private val bg = Color.rgb(8, 9, 12)
    private val panel = Color.rgb(18, 19, 24)
    private val red = Color.rgb(255, 48, 56)
    private val white = Color.rgb(235, 237, 242)
    private val muted = Color.rgb(145, 150, 162)
    private val green = Color.rgb(83, 220, 145)
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var terminal: TextView
    private lateinit var metrics: TextView
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView
    private var batteryPct = -1
    private var batteryTempC = Double.NaN
    private var charging = false
    private var rootAvailable = false
    private var boostInProgress = false
    private var boostAnimationDone = false
    private var boostWorkerDone = false
    private var boostButton: Button? = null
    private var pendingLaunchIntent: Intent? = null
    private var currentProfile = "BALANCED"
    private var fpsLine = "FPS: N/A (start Free Fire and run `fps` to probe SurfaceFlinger)"
    private val prefs by lazy { getSharedPreferences("doomx_state", MODE_PRIVATE) }
    private val log = mutableListOf<String>()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            batteryTempC = if (tenths != Int.MIN_VALUE) tenths / 10.0 else Double.NaN
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            renderMetrics()
        }
    }

    private val refresh = object : Runnable {
        override fun run() {
            renderMetrics()
            handler.postDelayed(this, 2500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        currentProfile = prefs.getString("profile", "BALANCED") ?: "BALANCED"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val header = TextView(this).apply {
            text = "DOOMX // ROOT OPTIMIZER\n────────────────────────────"
            setTextColor(red); textSize = 20f; typeface = Typeface.MONOSPACE; setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(header, matchWrap())
        val sub = TextView(this).apply {
