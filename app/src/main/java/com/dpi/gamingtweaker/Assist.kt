package com.dpi.gamingtweaker

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

// ============================ ASSIST PREFS ============================
class AssistPrefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("game_assist", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit { putBoolean("enabled", v) }

    var dnd: Boolean
        get() = sp.getBoolean("dnd", true)
        set(v) = sp.edit { putBoolean("dnd", v) }

    var noHeadsUp: Boolean
        get() = sp.getBoolean("no_heads_up", true)
        set(v) = sp.edit { putBoolean("no_heads_up", v) }

    var lockBrightness: Boolean
        get() = sp.getBoolean("lock_brightness", false)
        set(v) = sp.edit { putBoolean("lock_brightness", v) }

    var toolbox: Boolean
        get() = sp.getBoolean("toolbox", true)
        set(v) = sp.edit { putBoolean("toolbox", v) }

    var vibrate: Boolean
        get() = sp.getBoolean("vibrate", true)
        set(v) = sp.edit { putBoolean("vibrate", v) }

    var heatAlertC: Int
        get() = sp.getInt("heat_alert", 43)
        set(v) = sp.edit { putInt("heat_alert", v) }

    /** 0 = ปิดการเตือนพัก */
    var breakMin: Int
        get() = sp.getInt("break_min", 60)
        set(v) = sp.edit { putInt("break_min", v) }

    var batteryAlert: Int
        get() = sp.getInt("battery_alert", 15)
        set(v) = sp.edit { putInt("battery_alert", v) }

    /** คำสั่งคืนค่า DND/แจ้งเตือน/ความสว่างเดิม (null = ไม่ได้อยู่ใน Game Mode) */
    var restoreCmd: String?
        get() = sp.getString("restore_cmd", null)
        set(v) = sp.edit(commit = true) { putString("restore_cmd", v) }

    var sessionActive: Boolean
        get() = sp.getBoolean("session_active", false)
        set(v) = sp.edit(commit = true) { putBoolean("session_active", v) }

    /** เวลาเริ่มเล่น (ms, wall clock) เก็บไว้ให้ตัวจับเวลาไม่รีเซ็ตถ้า service ถูกรีสตาร์ตกลางเกม */
    var sessionStart: Long
        get() = sp.getLong("session_start", 0L)
        set(v) = sp.edit(commit = true) { putLong("session_start", v) }
}

// ============================ GAME MODE ============================
object GameMode {
    private val NUM = Regex("""-?\d+(\.\d+)?""")
    private val ZEN = mapOf("0" to "off", "1" to "priority", "2" to "none", "3" to "alarms")
    private val OK = Shell.Result(0, "", "")

    private const val SNAP =
        "echo \"zen=\$(settings get global zen_mode)\"; " +
            "echo \"hu=\$(settings get global heads_up_notifications_enabled)\"; " +
            "echo \"bm=\$(settings get system screen_brightness_mode)\"; " +
            "echo \"br=\$(settings get system screen_brightness)\""

    private fun restore(ns: String, k: String, v: String?) =
        if (v != null && NUM.matches(v)) "settings put $ns $k $v; " else "settings delete $ns $k; "

    suspend fun setDnd(on: Boolean) =
        Shell.exec("cmd notification set_dnd ${if (on) "priority" else "off"}")

    /** อ่านสถานะ DND จริงจากระบบ (zen_mode != 0 = เปิดอยู่) */
    suspend fun isDndOn(): Boolean? {
        val r = Shell.exec("settings get global zen_mode", 5)
        if (!r.ok) return null
        return r.out.trim().toIntOrNull()?.let { it != 0 }
    }

    suspend fun enter(ap: AssistPrefs): Shell.Result {
        if (ap.restoreCmd == null) {
            val r = Shell.exec(SNAP)
            if (!r.ok) return r
            val v = r.out.lines().associate { it.substringBefore('=') to it.substringAfter('=', "").trim() }
            ap.restoreCmd = "cmd notification set_dnd ${ZEN[v["zen"]] ?: "off"} >/dev/null 2>&1; " +
                restore("global", "heads_up_notifications_enabled", v["hu"]) +
                restore("system", "screen_brightness_mode", v["bm"]) +
                restore("system", "screen_brightness", v["br"])
        }
        val cmd = buildString {
            if (ap.dnd) append("cmd notification set_dnd priority >/dev/null 2>&1; ")
            if (ap.noHeadsUp) append("settings put global heads_up_notifications_enabled 0; ")
            // ล็อกความสว่าง = ปิด auto-brightness แล้วคงค่าปัจจุบันไว้ (ค่าเดิมถูกจำไว้ใน restoreCmd แล้ว)
            if (ap.lockBrightness) append("settings put system screen_brightness_mode 0; ")
            append("true")
        }
        return Shell.exec(cmd)
    }

    /** คืนค่าเดิม สำเร็จแล้วจึงล้าง restoreCmd (ถ้าล้มเหลวจะเก็บไว้ให้ลองใหม่ได้) */
    suspend fun exit(ap: AssistPrefs): Shell.Result {
        val cmd = ap.restoreCmd ?: return OK
        return Shell.exec(cmd + "true").also { if (it.ok) ap.restoreCmd = null }
    }
}

// ============================ HELPERS ============================
object Assist {
    data class Battery(val pct: Int, val tempC: Float?, val charging: Boolean)

    fun battery(ctx: Context): Battery {
        val i = runCatching { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
            ?: return Battery(-1, null, false)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return Battery(
            if (level >= 0 && scale > 0) level * 100 / scale else -1,
            if (temp == Int.MIN_VALUE) null else temp / 10f,
            plugged
        )
    }

    fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    @Suppress("DEPRECATION")
    fun buzz(ctx: Context) = runCatching {
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return@runCatching
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
        else v.vibrate(250)
    }

    /** รอ Shizuku ส่ง binder มาให้ (ตอน service/tile เพิ่งเริ่ม) แล้วค่อยตรวจสิทธิ์ */
    suspend fun ensureShell(): Shell.Mode = withContext(Dispatchers.IO) {
        if (Shell.mode != Shell.Mode.NONE && (Shell.mode != Shell.Mode.SHIZUKU || Shell.shizukuRunning())) {
            return@withContext Shell.mode
        }
        var i = 0
        while (i++ < 20 && !Shell.shizukuRunning()) delay(100)
        Shell.detect(force = true)
    }

    suspend fun selfTest(ctx: Context): String = buildString {
        appendLine("=== DPI Gaming Tweaker Self-Test ===")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        val mode = ensureShell()
        appendLine("Backend: $mode")
        appendLine("Overlay permission: ${Settings.canDrawOverlays(ctx)}")
        appendLine("Accessibility: ${isA11yEnabled(ctx)} | service live: ${GameDetectorService.instance != null}")
        val p = Prefs(ctx)
        val ap = AssistPrefs(ctx)
        appendLine("AutoApply=${p.autoApply} inGameProfile=${p.inGameProfile} gameDpi=${p.gameDpi} games=${p.gamePackages.size}")
        appendLine("Assist: enabled=${ap.enabled} session=${ap.sessionActive} gameMode=${ap.restoreCmd != null}")
        if (mode == Shell.Mode.NONE) {
            appendLine("ข้ามการทดสอบ shell (ไม่มี Shizuku/Root)")
            return@buildString
        }
        val tests = listOf(
            "wm density" to "wm density",
            "wm size" to "wm size",
            "settings read" to "settings get system pointer_speed",
            "DND support" to "cmd notification 2>&1 | grep -c set_dnd",
            "FPS (SurfaceFlinger)" to "service call SurfaceFlinger 1013",
            "Thermal zones" to "ls /sys/class/thermal | grep -c thermal_zone",
            "Foreground detect" to "dumpsys activity activities | grep -c ResumedActivity",
        )
        for ((name, cmd) in tests) {
            val r = Shell.exec(cmd, 8)
            val out = r.out.ifBlank { r.err }.lineSequence().take(2).joinToString(" / ").take(120)
            // grep -c คืน exit 1 เมื่อนับได้ 0 จึงถือว่า "ทำงานได้" ถ้ามีเอาต์พุต
            val pass = r.ok || (r.code == 1 && r.out.isNotBlank())
            appendLine("[${if (pass) "OK" else "FAIL ${r.code}"}] $name: $out")
        }
        val resumed = Tweaks.resumedPackages()
        appendLine("Resumed apps: ${resumed.joinToString().ifBlank { "-" }.take(120)}")
    }
}

// ============================ FLOATING TOOLBOX ============================
private val TB_NORMAL = 0xCC111122.toInt()
private val TB_WARN = 0xDDD50000.toInt()
private val TB_INFO = 0xDD0277BD.toInt()

class Toolbox(
    private val ctx: Context,
    private val wm: WindowManager,
    private val scope: CoroutineScope,
    private val windowType: Int,
    private val onClose: () -> Unit,
) {
    private var root: LinearLayout? = null
    private var bubble: TextView? = null
    private var flashUntil = 0L

    val shown: Boolean get() = root != null

    private fun bg(color: Int, radius: Float = 26f) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    private fun makeText(s: String, sizePx: Float) = TextView(ctx).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx) // px คงที่ ไม่ยืดตาม DPI
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun rowLp() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = 8 }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null) return
        val p = Prefs(ctx)

        val b = makeText("GAME ASSIST", 28f).apply {
            typeface = Typeface.MONOSPACE
            background = bg(TB_NORMAL)
            setPadding(22, 10, 22, 10)
        }
        val pn = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bg(0xEE111122.toInt(), 20f)
            setPadding(12, 12, 12, 12)
            visibility = View.GONE
        }

        fun action(title: String, color: Int, block: suspend () -> String) {
            pn.addView(makeText(title, 26f).apply {
                background = bg(color, 18f)
                gravity = Gravity.CENTER
                setPadding(20, 14, 20, 14)
                setOnClickListener {
                    scope.launch { flash(runCatching { block() }.getOrElse { "ผิดพลาด: ${it.message}" }) }
                }
            }, rowLp())
        }

        // อ่านค่าสดจาก Prefs ทุกครั้งที่กด (ผู้ใช้อาจแก้โปรไฟล์ระหว่างเกม)
        action("DPI เกม", 0xFFFF7A00.toInt()) {
            val d = Prefs(ctx).gameDpi
            if (Shell.exec("wm density $d").ok) "DPI = $d" else "เปลี่ยน DPI ไม่สำเร็จ"
        }
        action("DPI ปกติ", 0xFF0091EA.toInt()) {
            val pre = Prefs(ctx).preGameDpi
            if (Shell.exec(if (pre > 0) "wm density $pre" else "wm density reset").ok) "คืน DPI แล้ว" else "ไม่สำเร็จ"
        }
        action("RAM Boost", 0xFF00A152.toInt()) {
            val pp = Prefs(ctx)
            "เคลียร์ ${Tweaks.boostRam(pp.gamePackages + ctx.packageName)} แอป"
        }
        action("ห้ามรบกวน เปิด/ปิด", 0xFF6A1B9A.toInt()) {
            val now = GameMode.isDndOn() ?: false
            if (GameMode.setDnd(!now).ok) "DND ${if (!now) "เปิด" else "ปิด"}" else "สั่ง DND ไม่สำเร็จ"
        }
        action("รีเซ็ตฉุกเฉิน", 0xFFD50000.toInt()) {
            val r = Tweaks.emergencyReset(p)
            if (r.ok) GameMode.exit(AssistPrefs(ctx))
            if (r.ok) "คืนค่าโรงงานแล้ว" else "รีเซ็ตไม่สำเร็จ"
        }
        pn.addView(makeText("ซ่อนแผง", 24f).apply {
            gravity = Gravity.CENTER
            setPadding(20, 12, 20, 12)
            setOnClickListener { pn.visibility = View.GONE }
        }, rowLp())
        pn.addView(makeText("ปิดผู้ช่วย", 24f).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFFF8A80.toInt())
            setPadding(20, 12, 20, 12)
            setOnClickListener { onClose() }
        }, rowLp())

        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(b)
            addView(pn)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 240
            if (Build.VERSION.SDK_INT >= 28)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // ลาก = ย้ายตำแหน่ง, แตะ = เปิด/ปิดแผง
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx
                    val dy = e.rawY - ty
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) {
                        params.x = sx + dx.toInt()
                        params.y = sy + dy.toInt()
                        runCatching { wm.updateViewLayout(r, params) }
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    pn.visibility = if (pn.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            }
            true
        }

        try {
            wm.addView(r, params)
        } catch (e: Exception) {
            return // เพิ่มหน้าต่างไม่สำเร็จ: ไม่ทิ้งสถานะครึ่งๆ กลางๆ
        }
        root = r
        bubble = b
    }

    fun update(s: String, warn: Boolean) {
        val b = bubble ?: return
        if (SystemClock.elapsedRealtime() < flashUntil) return
        b.text = s
        b.background = bg(if (warn) TB_WARN else TB_NORMAL)
    }

    fun flash(msg: String, ms: Long = 2500) {
        val b = bubble ?: return
        flashUntil = SystemClock.elapsedRealtime() + ms
        b.text = msg
        b.background = bg(TB_INFO)
    }

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        bubble = null
    }
}

// ============================ DETECTOR + ASSIST SESSION ============================
class GameDetectorService : AccessibilityService() {
    companion object {
        @Volatile var instance: GameDetectorService? = null
            private set
        val live = MutableStateFlow(false)
        val gameRunning = MutableStateFlow(false)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private lateinit var prefs: Prefs
    private lateinit var ap: AssistPrefs
    private var pending: Job? = null
    @Volatile private var sessionJob: Job? = null
    @Volatile private var userClosed = false

    private val toolbox by lazy {
        Toolbox(
            this,
            getSystemService(WINDOW_SERVICE) as WindowManager,
            uiScope,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, // ทับเกมเต็มจอ/split-screen ได้ ไม่ต้องใช้สิทธิ์ Overlay
        ) {
            userClosed = true
            stopSession()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        ap = AssistPrefs(this)
        instance = this
        live.value = true
        scope.launch { Assist.ensureShell() }
        // ถ้าครั้งก่อน service ตายตอนอยู่ในเกม → ตรวจใหม่เพื่อคืนค่า/เริ่ม session ต่อ
        if (prefs.inGameProfile || ap.sessionActive || ap.restoreCmd != null) schedule(null, 1500)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || !::prefs.isInitialized) return
        if (!prefs.autoApply && !prefs.inGameProfile && !ap.enabled && !ap.sessionActive) return
        schedule(event.packageName?.toString(), 600)
    }

    private fun schedule(pkg: String?, delayMs: Long) {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs) // debounce
            withContext(NonCancellable) { mutex.withLock { evaluate(pkg) } }
        }
    }

    private suspend fun evaluate(pkg: String?) {
        val games = prefs.gamePackages
        val resumed = Tweaks.resumedPackages()
        val inGame = when {
            resumed.isNotEmpty() -> resumed.any { it in games } // รองรับ split-screen
            pkg != null && pkg in games -> true
            else -> return // อ่านสถานะไม่ได้ ไม่เดา
        }
        gameRunning.value = inGame

        // ส่วน DPI
        when {
            inGame && !prefs.inGameProfile && prefs.autoApply -> Tweaks.enterGameProfile(prefs)
            !inGame && prefs.inGameProfile -> Tweaks.exitGameProfile(prefs)
        }

        // ส่วนผู้ช่วยเล่นเกม
        when {
            inGame && ap.enabled && !ap.sessionActive -> {
                ap.sessionActive = true
                ap.sessionStart = System.currentTimeMillis()
                userClosed = false
                GameMode.enter(ap)
                startSession(forceToolbox = false)
            }
            inGame && ap.sessionActive && !userClosed && sessionJob?.isActive != true ->
                startSession(forceToolbox = false) // service ถูกรีสตาร์ตกลางเกม
            !inGame && (ap.sessionActive || ap.restoreCmd != null) -> {
                stopSession()
                // คืนค่าไม่สำเร็จ → คง sessionActive ไว้ให้ลองใหม่รอบถัดไป ไม่ปล่อยให้ DND ค้าง
                if (GameMode.exit(ap).ok) {
                    ap.sessionActive = false
                    ap.sessionStart = 0L
                    userClosed = false
                }
            }
        }
    }

    fun startSession(forceToolbox: Boolean = true) {
        uiScope.launch {
            if (sessionJob?.isActive == true) {
                if (forceToolbox) runCatching { toolbox.show() }
                return@launch
            }
            sessionJob = uiScope.launch { runSession(forceToolbox) }
        }
    }

    fun stopSession() {
        uiScope.launch {
            sessionJob?.cancel()
            sessionJob = null
            toolbox.hide()
        }
    }

    private suspend fun runSession(forceToolbox: Boolean) {
        if (ap.toolbox || forceToolbox) runCatching { toolbox.show() }
        // ใช้เวลาเริ่มเล่นที่บันทึกไว้ ถ้าเป็นการทดสอบ (ไม่มี session จริง) ใช้เวลาปัจจุบัน
        val startWall = if (ap.sessionActive && ap.sessionStart > 0) ap.sessionStart else System.currentTimeMillis()
        val breakMs = ap.breakMin * 60_000L
        var bat = Assist.battery(this)
        var tick = 0
        var hotWarned = false
        var lowWarned = false
        var nextBreak = if (breakMs > 0) {
            val el = System.currentTimeMillis() - startWall
            (el / breakMs + 1) * breakMs
        } else Long.MAX_VALUE
        try {
            while (currentCoroutineContext().isActive) {
                if (++tick % 5 == 0) bat = Assist.battery(this)
                val elapsed = System.currentTimeMillis() - startWall
                val t = bat.tempC
                val hot = t != null && t >= ap.heatAlertC
                val low = !bat.charging && bat.pct in 0..ap.batteryAlert

                if (hot && !hotWarned) {
                    alert("เครื่องร้อน ${"%.1f".format(t ?: 0f)}°C ลองลดกราฟิกหรือพักเครื่อง")
                    hotWarned = true
                }
                if (t != null && t < ap.heatAlertC - 2) hotWarned = false
                if (low && !lowWarned) {
                    alert("แบตเหลือ ${bat.pct}% เสียบชาร์จก่อนแบตหมดกลางเกม")
                    lowWarned = true
                }
                if (bat.charging || bat.pct > ap.batteryAlert + 3) lowWarned = false
                if (breakMs > 0 && elapsed >= nextBreak) {
                    alert("เล่นมา ${elapsed / 60_000} นาทีแล้ว พักสายตาสักครู่", 10_000)
                    nextBreak += breakMs
                }

                val temp = t?.let { "%.0f°C".format(it) } ?: "--°C"
                val pct = if (bat.pct >= 0) "${bat.pct}%" else "--%"
                toolbox.update("${Assist.fmt(elapsed)}  $temp  $pct", hot || low)
                delay(1000)
            }
        } finally {
            toolbox.hide()
        }
    }

    private fun alert(msg: String, ms: Long = 4000) {
        if (ap.vibrate) Assist.buzz(this)
        if (toolbox.shown) toolbox.flash(msg, ms)
        else Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        live.value = false
        gameRunning.value = false
        sessionJob?.cancel()
        runCatching { toolbox.hide() }
        if (::prefs.isInitialized) {
            val p = prefs
            val a = ap
            CoroutineScope(Dispatchers.IO).launch {
                withTimeoutOrNull(5000) {
                    if (p.inGameProfile) Tweaks.exitGameProfile(p)
                    if (a.sessionActive || a.restoreCmd != null) {
                        if (GameMode.exit(a).ok) {
                            a.sessionActive = false
                            a.sessionStart = 0L
                        }
                    }
                }
            }
        }
        scope.cancel()
        uiScope.cancel()
        super.onDestroy()
    }
}

// ============================ QUICK SETTINGS TILE ============================
class EmergencyTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        setTile("DPI Reset", Tile.STATE_INACTIVE)
    }

    override fun onClick() {
        super.onClick()
        setTile("กำลังรีเซ็ต...", Tile.STATE_ACTIVE)
        val app = applicationContext
        // ไม่ผูกกับ lifecycle ของ tile: ถ้าปิดแผงไปแล้ว การรีเซ็ตก็ยังทำต่อจนเสร็จ
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            val ok = Assist.ensureShell() != Shell.Mode.NONE && Tweaks.emergencyReset(Prefs(app)).ok
            if (ok) {
                val ap = AssistPrefs(app)
                GameDetectorService.instance?.stopSession()
                if (GameMode.exit(ap).ok) { ap.sessionActive = false; ap.sessionStart = 0L }
            }
            setTile(if (ok) "รีเซ็ตแล้ว" else "ไม่มีสิทธิ์", Tile.STATE_INACTIVE)
        }
    }

    private fun setTile(label: String, state: Int) {
        qsTile?.let {
            it.label = label
            it.state = state
            runCatching { it.updateTile() }
        }
    }
}
