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
            text = "LIVE DEVICE TELEMETRY  •  MAGISK / SU DIAGNOSTICS"
            setTextColor(muted); textSize = 10f; setPadding(0, dp(4), 0, dp(12))
        }
        root.addView(sub, matchWrap())
        metrics = TextView(this).apply {
            setTextColor(green); textSize = 12f; typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12)); setBackgroundColor(panel)
        }
        root.addView(metrics, matchWrap())
        boostButton = Button(this).apply {
            text = "⚡  OPTIMIZE & LAUNCH FREE FIRE"
            textSize = 12f
            setTextColor(white)
            setBackgroundColor(red)
            setOnClickListener { optimizeAndLaunchFreeFire() }
        }
        root.addView(boostButton, matchWrap().apply { topMargin = dp(10); bottomMargin = dp(4) })
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8)) }
        addButton(controls, "STATUS") { executeCommand("status") }
        addButton(controls, "ROOT CHECK") { executeCommand("root") }
        addButton(controls, "FPS PROBE") { executeCommand("fps") }
        root.addView(controls, matchWrap())
        val controls2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        addButton(controls2, "BALANCED") { setProfile("BALANCED") }
        addButton(controls2, "PERFORMANCE") { setProfile("PERFORMANCE") }
        addButton(controls2, "BATTERY") { setProfile("BATTERY SAVER") }
        root.addView(controls2, matchWrap())
        scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(panel) }
        terminal = TextView(this).apply {
            setTextColor(white); textSize = 12f; typeface = Typeface.MONOSPACE
            setPadding(dp(12), dp(12), dp(12), dp(12)); text = ""
        }
        scroll.addView(terminal, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val scrollParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) }
        root.addView(scroll, scrollParams)
        val prompt = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, 0) }
        val prefix = TextView(this).apply { text = "dx\$ "; setTextColor(red); textSize = 14f; typeface = Typeface.MONOSPACE }
        prompt.addView(prefix, LinearLayout.LayoutParams(-2, dp(48)))
        input = EditText(this).apply {
            setTextColor(white); setHintTextColor(muted); hint = "help / battery / thermal / memory / fps"
            textSize = 12f; typeface = Typeface.MONOSPACE; setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND; setBackgroundColor(panel); setPadding(dp(8), 0, dp(8), 0)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    val command = text.toString().trim(); setText(""); executeCommand(command); true
                } else false
            }
        }
        prompt.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
        val run = Button(this).apply { text = "RUN"; setTextColor(white); setBackgroundColor(red); setOnClickListener { val c = input.text.toString().trim(); input.setText(""); executeCommand(c) } }
        prompt.addView(run, LinearLayout.LayoutParams(dp(64), dp(48)))
        root.addView(prompt, matchWrap())
        val footer = TextView(this).apply { text = "READ-ONLY DIAGNOSTICS BY DEFAULT • NO FAKE FPS • RESTORE = APP PROFILE RESET"; setTextColor(muted); textSize = 9f; setPadding(0, dp(8), 0, 0) }
        root.addView(footer, matchWrap())
        setContentView(root)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        append("DOOMX terminal initialized.")
        append("Device: ${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        append("Type `help` to list supported commands. Root check runs in background.")
        handler.post(refresh)
        executeCommand("root")
    }

    private fun addButton(row: LinearLayout, label: String, action: () -> Unit) {
        val b = Button(this).apply {
            text = label; textSize = 9f; setTextColor(white); setBackgroundColor(Color.rgb(48, 20, 25));
            setPadding(dp(3), 0, dp(3), 0); setOnClickListener { action() }
        }
        row.addView(b, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) })
    }

    private fun executeCommand(raw: String) {
        val cmd = raw.trim().lowercase(Locale.US)
        if (cmd.isEmpty()) return
        append("dx\$ $raw")
        when {
            cmd == "help" -> append("Commands: help, status, root, rootdiag, battery, thermal, memory, storage, cpu, fps, optimize ff, boost, profile, optimize balanced|performance|battery, clearlog, restore, clearcache")
            cmd == "status" -> { renderMetrics(); append("${metrics.text}\nProfile: $currentProfile\nRoot: ${if (rootAvailable) "AVAILABLE" else "NOT CONFIRMED"}\n$fpsLine") }
            cmd == "battery" -> append("Battery: ${if (batteryPct >= 0) "$batteryPct%" else "N/A"} | ${if (charging) "charging/full" else "not charging"} | battery temp: ${fmtTemp(batteryTempC)}")
            cmd == "thermal" -> append(thermalReport())
            cmd == "memory" -> append(memoryReport())
            cmd == "storage" -> append(storageReport())
            cmd == "cpu" -> runDiagnostic("CPU / system properties", "getprop ro.hardware; getprop ro.product.cpu.abi; cat /proc/loadavg; head -n 5 /proc/stat", false)
            cmd == "rootdiag" -> runDiagnostic("Root read-only diagnostics", "id; getprop ro.product.model; getprop ro.build.version.release; getprop ro.hardware; dumpsys thermalservice", true)
            cmd == "root" -> runDiagnostic("Root availability", "id", true) { output, exit ->
                rootAvailable = exit == 0 && output.contains("uid=0")
                append("Root status: ${if (rootAvailable) "ROOT AVAILABLE (uid=0)" else "ROOT NOT CONFIRMED — grant the Magisk prompt if one appears"} (exit $exit)")
                if (output.isNotBlank()) append(output.take(1200))
                renderMetrics()
            }
            cmd == "fps" -> probeFps()
            cmd == "optimize ff" || cmd == "boost" -> optimizeAndLaunchFreeFire()
            cmd == "profile" -> append("Active DOOMX profile: $currentProfile. Profiles are advisory/app-side; no hidden kernel values are changed.")
            cmd.startsWith("optimize ") -> {
                val choice = cmd.removePrefix("optimize ").trim()
                when (choice) {
                    "balanced" -> setProfile("BALANCED")
                    "performance" -> setProfile("PERFORMANCE")
                    "battery", "battery saver" -> setProfile("BATTERY SAVER")
                    else -> append("Usage: optimize balanced|performance|battery")
                }
            }
            cmd == "restore" -> { setProfile("BALANCED"); append("Restore completed: DOOMX profile reset to BALANCED. No system settings were changed by profile selection.") }
            cmd == "clearcache" -> {
                val bytes = cacheSize(cacheDir)
                val ok = clearOwnCache(cacheDir)
                append("App-private cache cleanup: ${if (ok) "SUCCESS" else "FAILED"}; before=${humanBytes(bytes)}. Only DOOMX's own cache is targeted; game/system data is untouched.")
            }
            cmd == "clearlog" -> { log.clear(); terminal.text = ""; append("Visible terminal log cleared.") }
            else -> append("Unknown command. This terminal only accepts documented, allowlisted commands. Type `help`.")
        }
    }

    /**
     * Runs a visible 5-second terminal sequence, attempts Android's supported
     * Game Mode performance setting through Magisk su, then opens the installed game.
     * It deliberately avoids undocumented CPU/GPU governor and thermal-limit writes.
     */
    private fun optimizeAndLaunchFreeFire() {
        if (boostInProgress) {
            append("Boost already running; please wait.")
            return
        }
        val packageName = listOf("com.dts.freefireth", "com.dts.freefiremax").firstOrNull { pkg ->
            try {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(pkg, 0)
                true
            } catch (_: Exception) { false }
        }
        if (packageName == null) {
            append("Free Fire / Free Fire MAX was not found. Install the game, then retry.")
            return
        }
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        if (launch == null) {
            append("Could not find a launch activity for $packageName.")
            return
        }

        boostInProgress = true
        boostAnimationDone = false
        boostWorkerDone = false
        pendingLaunchIntent = launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        boostButton?.isEnabled = false
        boostButton?.text = "RUNNING 5-SECOND BOOST..."
        append("DOOMX BOOST sequence started for $packageName")
        append("[00%] Initializing safe game-performance checks...")

        val animationLines = listOf(
            "[20%] Checking Magisk root permission...",
            "[40%] Reading Android Game Mode support...",
            "[60%] Requesting supported PERFORMANCE game mode...",
            "[80%] Checking optimization result; no hidden kernel tweaks...",
            "[100%] Five-second terminal sequence complete."
        )
        animationLines.forEachIndexed { index, line ->
            handler.postDelayed({ append(line) }, (index + 1) * 1000L)
        }
        handler.postDelayed({
            boostAnimationDone = true
            finishBoostIfReady()
        }, 5000L)

        io.execute {
            val rootResult = shell("id", true)
            val hasRoot = rootResult.second == 0 && rootResult.first.contains("uid=0")
            var resultText: String
            if (!hasRoot) {
                resultText = "Root optimization skipped: Magisk root was not granted or uid=0 was unavailable."
            } else {
                rootAvailable = true
                // Safety gate: do not request a higher performance mode when Android reports a severe thermal condition.
                val thermal = shell("dumpsys thermalservice", true)
                val thermalText = thermal.first.uppercase(Locale.US)
                val hot = listOf("THERMAL_STATUS_SEVERE", "THERMAL_STATUS_CRITICAL",
                    "THERMAL_STATUS_EMERGENCY", "THERMAL_STATUS_SHUTDOWN").any { thermalText.contains(it) }
                if (hot) {
                    resultText = "Root confirmed, but performance request was skipped because thermal diagnostics report a severe condition. Let the device cool first."
                } else {
                    // Android/OEM Game Mode support varies. Capture and report the actual command result.
                    val gameMode = shell("cmd game mode performance $packageName", true)
                    resultText = if (gameMode.second == 0 && !gameMode.first.contains("Error", true) &&
                        !gameMode.first.contains("Unknown command", true)) {
                        val verify = shell("cmd game mode $packageName", true)
                        "Android Game Mode PERFORMANCE request accepted for $packageName. " +
                            (verify.first.takeIf { it.isNotBlank() }?.let { "Reported mode: ${it.trim().take(120)}. " } ?: "") +
                            "Actual FPS gains depend on the device and game."
                    } else {
                        "Root confirmed, but this ROM does not expose a usable Game Mode performance command. No undocumented CPU/GPU/thermal settings were changed. " +
                            (gameMode.first.ifBlank { "command exit ${gameMode.second}" }).take(240)
                    }
                }
            }
            runOnUiThread {
                append(resultText)
                renderMetrics()
                boostWorkerDone = true
                finishBoostIfReady()
            }
        }
    }

    private fun finishBoostIfReady() {
        if (!boostInProgress || !boostAnimationDone || !boostWorkerDone) return
        val intent = pendingLaunchIntent
        pendingLaunchIntent = null
        boostInProgress = false
        boostButton?.isEnabled = true
        boostButton?.text = "⚡  OPTIMIZE & LAUNCH FREE FIRE"
        if (intent != null) {
            try {
                append("Launching Free Fire now...")
                startActivity(intent)
            } catch (e: Exception) {
                append("Could not launch the game: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun setProfile(profile: String) {
        currentProfile = profile
        prefs.edit().putString("profile", profile).apply()
        append("Profile set: $profile. Applied as DOOMX guidance only; no undocumented system properties or CPU governors were modified.")
        renderMetrics()
    }

    private fun runDiagnostic(title: String, command: String, asRoot: Boolean, callback: ((String, Int) -> Unit)? = null) {
        append("[$title] running...")
        io.execute {
            val result = shell(command, asRoot)
            runOnUiThread {
                if (callback != null) callback(result.first, result.second)
                else append("$title (exit ${result.second}):\n${result.first.ifBlank { "<no output>" }}")
            }
        }
    }

    private fun shell(command: String, asRoot: Boolean): Pair<String, Int> {
        return try {
            val proc = if (asRoot) ProcessBuilder("su", "-c", command).start() else ProcessBuilder("sh", "-c", command).start()
            val stdout = proc.inputStream.bufferedReader().use { it.readText() }
            val stderr = proc.errorStream.bufferedReader().use { it.readText() }
            val exit = proc.waitFor()
            (stdout + if (stderr.isNotBlank()) "\n[stderr] $stderr" else "") to exit
        } catch (e: Exception) { ("${e.javaClass.simpleName}: ${e.message ?: "command unavailable"}") to 127 }
    }

    private fun probeFps() {
        append("FPS probe: checking Free Fire SurfaceFlinger layers; this may be unavailable on some ROMs.")
        io.execute {
            val packages = listOf("com.dts.freefireth", "com.dts.freefiremax")
            val listResult = shell("dumpsys SurfaceFlinger --list", false)
            val layer = listResult.first.lineSequence().firstOrNull { line -> packages.any { line.contains(it, ignoreCase = true) } }
            if (listResult.second != 0 || layer == null) {
                runOnUiThread { fpsLine = "FPS: N/A (Free Fire layer not found; launch the game and retry)"; append(fpsLine); renderMetrics() }
                return@execute
            }
            val escapedLayer = layer.replace("'", "'\\''")
            val latency = shell("dumpsys SurfaceFlinger --latency '$escapedLayer'", false)
            val timestamps = latency.first.lineSequence().drop(1).mapNotNull { line ->
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.size < 2) null else fields.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0L }
            }.toList().distinct().sorted()
            val fps = if (latency.second == 0 && timestamps.size >= 3) {
                val window = timestamps.takeLast(60)
                val span = (window.last() - window.first()).toDouble() / 1_000_000_000.0
                if (span > 0.0) (window.size - 1) / span else null
            } else null
            runOnUiThread {
                fpsLine = if (fps != null && fps.isFinite() && fps in 1.0..360.0) "FPS: ${"%.1f".format(Locale.US, fps)} (SurfaceFlinger frame timestamps; approximate)" else "FPS: N/A (frame timestamps unavailable or insufficient)"
                append("Matched layer: ${layer.take(180)}")
                append(fpsLine)
                renderMetrics()
            }
        }
    }

    private fun renderMetrics() {
        if (!::metrics.isInitialized) return
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo(); activityManager.getMemoryInfo(mi)
        val totalRam = if (Build.VERSION.SDK_INT >= 16) mi.totalMem else 0L
        val usedRam = if (totalRam > 0L) max(0L, totalRam - mi.availMem) else -1L
        val cpu = readCpuLoad()
        val thermal = getSystemService(POWER_SERVICE) as PowerManager
        val thermalText = if (Build.VERSION.SDK_INT >= 29) {
            val s = thermal.currentThermalStatus
            when (s) { 0 -> "NONE"; 1 -> "LIGHT"; 2 -> "MODERATE"; 3 -> "SEVERE"; 4 -> "CRITICAL"; 5 -> "EMERGENCY"; 6 -> "SHUTDOWN"; else -> "N/A" }
        } else "API<29"
        val data = StatFs(filesDir.absolutePath)
        val totalStorage = data.totalBytes
        val freeStorage = data.availableBytes
        metrics.text = "DEVICE  ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "ANDROID ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n" +
            "CPU LOAD ${if (cpu >= 0) "$cpu%" else "N/A"}    RAM ${if (usedRam >= 0) "${humanBytes(usedRam)} / ${humanBytes(totalRam)}" else "N/A"}\n" +
            "BATTERY ${if (batteryPct >= 0) "$batteryPct%" else "N/A"} ${if (charging) "CHARGING" else ""}  TEMP ${fmtTemp(batteryTempC)}\n" +
            "THERMAL $thermalText    DATA FREE ${humanBytes(freeStorage)} / ${humanBytes(totalStorage)}\n" +
            "ROOT ${if (rootAvailable) "CONFIRMED" else "NOT CONFIRMED"}    PROFILE $currentProfile\n" + fpsLine
    }

    private fun thermalReport(): String {
        val p = getSystemService(POWER_SERVICE) as PowerManager
        val status = if (Build.VERSION.SDK_INT >= 29) p.currentThermalStatus.toString() else "unsupported by Android API"
        return "Android thermal status code: $status\nBattery temperature: ${fmtTemp(batteryTempC)}\nNote: battery temperature is not CPU/GPU temperature; many phones do not expose individual sensor temperatures to third-party apps."
    }

    private fun memoryReport(): String {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val m = ActivityManager.MemoryInfo(); am.getMemoryInfo(m)
        return "RAM total: ${humanBytes(m.totalMem)}\nRAM available: ${humanBytes(m.availMem)}\nRAM used estimate: ${humanBytes(max(0L, m.totalMem - m.availMem))}\nLow-memory state: ${m.lowMemory}"
    }

    private fun storageReport(): String {
        val s = StatFs(filesDir.absolutePath)
        return "Data volume total: ${humanBytes(s.totalBytes)}\nAvailable: ${humanBytes(s.availableBytes)}\nUsed estimate: ${humanBytes(s.totalBytes - s.availableBytes)}\nScope: filesystem containing DOOMX app data; OEM storage reporting may differ."
    }

    private var lastTotal: Long = -1
    private var lastIdle: Long = -1
    private fun readCpuLoad(): Int {
        return try {
            val parts = java.io.File("/proc/stat").readLines().first().trim().split(Regex("\\s+")).drop(1).map { it.toLong() }
            val idle = parts[3] + (parts.getOrNull(4) ?: 0L)
            val total = parts.sum()
            if (lastTotal < 0 || total <= lastTotal) { lastTotal = total; lastIdle = idle; -1 }
            else {
                val dt = total - lastTotal; val di = idle - lastIdle
                lastTotal = total; lastIdle = idle
                if (dt > 0) (((dt - di).toDouble() / dt) * 100).toInt().coerceIn(0, 100) else -1
            }
        } catch (_: Exception) { -1 }
    }

    private fun append(message: String) {
        val line = "[${java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date())}] $message"
        log.add(line)
        while (log.size > 300) log.removeAt(0)
        if (::terminal.isInitialized) {
            terminal.text = log.joinToString("\n")
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun clearOwnCache(dir: java.io.File): Boolean {
        return try { dir.listFiles()?.forEach { it.deleteRecursively() }; true } catch (_: Exception) { false }
    }
    private fun cacheSize(dir: java.io.File): Long = try { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } } catch (_: Exception) { 0L }
    private fun fmtTemp(v: Double): String = if (v.isNaN()) "N/A" else "%.1f°C".format(Locale.US, v)
    private fun humanBytes(v: Long): String {
        if (v < 0) return "N/A"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var n = v.toDouble(); var i = 0
        while (n >= 1024 && i < units.lastIndex) { n /= 1024; i++ }
        return "%.1f %s".format(Locale.US, n, units[i])
    }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        io.shutdownNow()
        super.onDestroy()
    }
}
