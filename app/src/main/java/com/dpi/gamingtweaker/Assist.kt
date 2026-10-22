package com.dpi.gamingtweaker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.edit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

// ============================ PREF DELEGATES ============================
internal class BoolPref(
    private val sp: SharedPreferences, private val key: String, private val def: Boolean, private val commit: Boolean = false
) : ReadWriteProperty<Any?, Boolean> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = sp.getBoolean(key, def)
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) = sp.edit(commit) { putBoolean(key, value) }
}

internal class IntPref(private val sp: SharedPreferences, private val key: String, private val def: Int) : ReadWriteProperty<Any?, Int> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): Int = sp.getInt(key, def)
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) = sp.edit { putInt(key, value) }
}

internal class StrPref(
    private val sp: SharedPreferences, private val key: String, private val commit: Boolean = false
) : ReadWriteProperty<Any?, String?> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): String? = sp.getString(key, null)
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: String?) = sp.edit(commit) { putString(key, value) }
}

// ============================ SESSION HISTORY ============================
data class SessionRecord(
    val game: String, val startedAt: Long, val durationMs: Long,
    val avgFps: Int, val avgPing: Int, val maxPing: Int, val maxTemp: Float, val batteryUsed: Int,
) {
    fun toJson(): JSONObject = JSONObject().put("g", game).put("s", startedAt).put("d", durationMs)
        .put("f", avgFps).put("p", avgPing).put("mp", maxPing).put("t", maxTemp.toDouble()).put("b", batteryUsed)

    companion object {
        fun from(o: JSONObject) = SessionRecord(
            o.optString("g"), o.optLong("s"), o.optLong("d"), o.optInt("f"),
            o.optInt("p"), o.optInt("mp"), o.optDouble("t", 0.0).toFloat(), o.optInt("b")
        )
    }
}

// ============================ ASSIST PREFS ============================
class AssistPrefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("game_assist", Context.MODE_PRIVATE)

    var enabled by BoolPref(sp, "enabled", true)
    var dnd by BoolPref(sp, "dnd", true)
    var noHeadsUp by BoolPref(sp, "no_heads_up", true)
    var lockBrightness by BoolPref(sp, "lock_brightness", false)
    var rotationLock by BoolPref(sp, "rotation_lock", false)
    var androidGameMode by BoolPref(sp, "android_game_mode", true)
    var boostOnStart by BoolPref(sp, "boost_on_start", false)
    var toolbox by BoolPref(sp, "toolbox", true)
    var toolboxFps by BoolPref(sp, "toolbox_fps", true)
    var heatAlertC by IntPref(sp, "heat_alert", 43)
    var pingAlertMs by IntPref(sp, "ping_alert", 150)
    var breakMin by IntPref(sp, "break_min", 60)
    var batteryAlert by IntPref(sp, "battery_alert", 15)
    var clickIntervalMs by IntPref(sp, "click_interval", 60)
    var startMacroId by StrPref(sp, "start_macro")
    var exitMacroId by StrPref(sp, "exit_macro")
    var tbX by IntPref(sp, "tb_x", 24)
    var tbY by IntPref(sp, "tb_y", 200)
    var clickX by IntPref(sp, "click_x", 500)
    var clickY by IntPref(sp, "click_y", 1000)
    var restoreCmd by StrPref(sp, "restore_cmd", commit = true)
    var sessionActive by BoolPref(sp, "session_active", false, commit = true)
    private var historyRaw by StrPref(sp, "history")

    fun history(): List<SessionRecord> = runCatching {
        val arr = JSONArray(historyRaw ?: "[]")
        List(arr.length()) { SessionRecord.from(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun addHistory(r: SessionRecord) {
        val list = (listOf(r) + history()).take(15)
        historyRaw = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
    }

    fun clearHistory() { historyRaw = null }
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
            "echo \"rot=\$(settings get system accelerometer_rotation)\""

    private fun restore(ns: String, k: String, v: String?) =
        if (v != null && NUM.matches(v)) "settings put $ns $k $v; " else "settings delete $ns $k; "

    suspend fun setDnd(on: Boolean) = Shell.exec("cmd notification set_dnd ${if (on) "priority" else "off"}")

    suspend fun enter(ap: AssistPrefs, game: String?): Shell.Result {
        val pkg = game?.takeIf { Tweaks.PKG_RE.matches(it) }
        val useGameMode = ap.androidGameMode && pkg != null && Build.VERSION.SDK_INT >= 31
        if (ap.restoreCmd == null) {
            val r = Shell.exec(SNAP)
            if (!r.ok) return r
            val v = r.out.lines().associate { it.substringBefore('=') to it.substringAfter('=', "").trim() }
            ap.restoreCmd = "cmd notification set_dnd ${ZEN[v["zen"]] ?: "off"} >/dev/null 2>&1; " +
                restore("global", "heads_up_notifications_enabled", v["hu"]) +
                restore("system", "screen_brightness_mode", v["bm"]) +
                restore("system", "accelerometer_rotation", v["rot"]) +
                (if (useGameMode) "cmd game mode standard $pkg >/dev/null 2>&1; " else "")
        }
        val cmd = buildString {
            if (ap.dnd) append("cmd notification set_dnd priority >/dev/null 2>&1; ")
            if (ap.noHeadsUp) append("settings put global heads_up_notifications_enabled 0; ")
            if (ap.lockBrightness) append("settings put system screen_brightness_mode 0; ")
            if (ap.rotationLock) append("settings put system accelerometer_rotation 0; ")
            if (useGameMode) append("cmd game mode performance $pkg >/dev/null 2>&1; ")
            append("true")
        }
        return Shell.exec(cmd)
    }

    suspend fun exit(ap: AssistPrefs): Shell.Result {
        val cmd = ap.restoreCmd ?: return OK
        return Shell.exec(cmd + "true").also { if (it.ok) ap.restoreCmd = null }
    }
}

// ============================ HELPERS ============================
object Assist {
    data class Battery(val pct: Int, val tempC: Float?, val charging: Boolean)

    /** หน้าต่างของระบบที่โผล่ทับเกมชั่วคราว ไม่ใช่การสลับไปแอปอื่นจริง */
    fun isTransient(ctx: Context, pkg: String): Boolean =
        pkg == "android" || pkg == "com.android.systemui" || pkg.contains("inputmethod") ||
            pkg.contains("keyboard") || pkg.contains("permissioncontroller") ||
            pkg.contains("packageinstaller") || pkg == "com.google.android.gms" ||
            pkg.startsWith("com.miui.") && pkg.contains("notification") || pkg == ctx.packageName

    /** แอปที่ไม่ควรนับเป็นเกม: ตัวเอง, หน้าจอหลัก (launcher), SystemUI, แป้นพิมพ์, ตั้งค่า */
    fun isIgnorable(ctx: Context, pkg: String): Boolean {
        if (pkg == ctx.packageName || pkg == "android" || pkg == "com.android.systemui") return true
        if (pkg.contains("inputmethod") || pkg.contains("keyboard") || pkg == "com.android.settings") return true
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launcher = runCatching {
            ctx.packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }.getOrNull()
        return pkg == launcher
    }

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
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    suspend fun ensureShell(): Shell.Mode = withContext(Dispatchers.IO) {
        if (Shell.mode != Shell.Mode.NONE) return@withContext Shell.mode
        var i = 0
        while (i++ < 20 && !Shell.shizukuRunning()) delay(100)
        Shell.detect(force = true)
    }

    suspend fun fullReset(ctx: Context): Shell.Result {
        val r = Tweaks.emergencyReset(Prefs(ctx))
        if (r.ok) GameMode.exit(AssistPrefs(ctx))
        GameDetectorService.manualDpi = false
        return r
    }

    suspend fun selfTest(ctx: Context): String = buildString {
        appendLine("=== DPI Gaming Tweaker 2.0 Self-Test ===")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Stable density: ${DisplayMetrics.DENSITY_DEVICE_STABLE}")
        val mode = ensureShell()
        appendLine("Backend: $mode")
        appendLine("Overlay permission: ${Settings.canDrawOverlays(ctx)}")
        appendLine("Accessibility: ${isA11yEnabled(ctx)} | service live: ${GameDetectorService.instance != null}")
        val ap = AssistPrefs(ctx)
        appendLine("Assist: enabled=ap.enabledsession={ap.sessionActive} gameMode=${ap.restoreCmd != null}")
        if (mode == Shell.Mode.NONE) {
            appendLine("ข้ามการทดสอบ shell (ไม่มี Shizuku/Root)")
            return@buildString
        }
        val tests = listOf(
            "wm density" to "wm density",
            "wm size" to "wm size",
            "settings read" to "settings get system pointer_speed",
            "DND support" to "cmd notification 2>&1 | grep -c set_dnd",
            "Android Game Mode" to "cmd game 2>&1 | grep -c mode",
            "FPS (SurfaceFlinger)" to "service call SurfaceFlinger 1013",
            "Screenshot tool" to "which screencap",
            "Thermal zones" to "ls /sys/class/thermal | grep -c thermal_zone",
            "Foreground detect" to "dumpsys activity activities | grep -c ResumedActivity",
        )
        for ((name, cmd) in tests) {
            val r = Shell.exec(cmd, 8)
            val out = r.out.ifBlank { r.err }.lineSequence().take(2).joinToString(" / ").take(120)
            appendLine("[${if (r.ok) "OK" else "FAIL ${r.code}"}] $name: $out")
        }
    }
}

// ============================ AUTO CLICKER TARGET ============================
class AutoClickerTarget(
    private val ctx: Context,
    private val wm: WindowManager,
    private val ap: AssistPrefs,
    private val service: AccessibilityService,
) {
    private val unit = DisplayMetrics.DENSITY_DEVICE_STABLE / 160f
    private fun dp(v: Int) = (v * unit).roundToInt()

    private var targetView: View? = null
    private var targetParams: WindowManager.LayoutParams? = null
    var isRunning = false
        private set
    private var clickJob: Job? = null

    val shown: Boolean get() = targetView != null

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (targetView != null) return
        val sz = dp(52)
        val view = TextView(ctx).apply {
            text = "🎯"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 22f * unit)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x88FF1744.toInt())
                setStroke(dp(2), 0xFFFFD600.toInt())
            }
        }
        val params = WindowManager.LayoutParams(
            sz, sz,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ap.clickX
            y = ap.clickY
            if (Build.VERSION.SDK_INT >= 28)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    params.x = sx + (e.rawX - tx).toInt()
                    params.y = sy + (e.rawY - ty).toInt()
                    runCatching { wm.updateViewLayout(view, params) }
                    ap.clickX = params.x
                    ap.clickY = params.y
                }
            }
            true
        }

        wm.addView(view, params)
        targetView = view
        targetParams = params
    }

    /** ตอนรันให้เป้าไม่รับทัช เพื่อไม่ให้ดักนิ้วที่กำลังเล่นเกม ตอนหยุดค่อยให้ลากได้ */
    private fun setTouchable(touchable: Boolean) {
        val v = targetView ?: return
        val p = targetParams ?: return
        p.flags = if (touchable) {
            p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { wm.updateViewLayout(v, p) }
    }

    fun hide() {
        stop()
        targetView?.let { runCatching { wm.removeView(it) } }
        targetView = null
        targetParams = null
    }

    fun toggle(scope: CoroutineScope): Boolean {
        if (isRunning) stop() else start(scope)
        return isRunning
    }

    fun start(scope: CoroutineScope) {
        if (isRunning) return
        isRunning = true
        targetView?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x8800E676.toInt())
            setStroke(dp(3), 0xFFFFFFFF.toInt())
        }
        setTouchable(false)
        clickJob = scope.launch(Dispatchers.Default) {
            val half = dp(52) / 2f
            while (isActive) {
                val p = targetParams ?: break
                val cx = p.x + half
                val cy = p.y + half
                val t0 = SystemClock.uptimeMillis()
                // รอให้ท่าแตะจบก่อนค่อยส่งท่าใหม่ ไม่งั้นระบบจะยกเลิกท่าเก่าทิ้งจนสะดุด
                awaitTap(cx, cy)
                val spent = SystemClock.uptimeMillis() - t0
                delay((ap.clickIntervalMs - spent).coerceAtLeast(5L))
            }
        }
    }

    fun stop() {
        isRunning = false
        clickJob?.cancel()
        clickJob = null
        setTouchable(true)
        targetView?.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0x88FF1744.toInt())
            setStroke(dp(2), 0xFFFFD600.toInt())
        }
    }

    /** แตะสั้น 10ms แล้วรอผลจากระบบ (สำเร็จ/ถูกยกเลิก) ค่อยกลับ */
    private suspend fun awaitTap(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < 24) return
        val path = Path().apply { moveTo(x, y); lineTo(x, y) }
        val desc = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 10))
            .build()
        withTimeoutOrNull(300L) {
            suspendCancellableCoroutine<Unit> { cont ->
                val ok = service.dispatchGesture(desc, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { if (cont.isActive) cont.resume(Unit) {} }
                    override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(Unit) {} }
                }, null)
                if (!ok && cont.isActive) cont.resume(Unit) {}
            }
        }
    }
}

// ============================ FLOATING TOOLBOX ============================
private val TB_NORMAL = 0xCC111122.toInt()
private val TB_WARN = 0xDDD50000.toInt()
private val TB_INFO = 0xDD0277BD.toInt()
private val C_ORANGE = 0xFFFF7A00.toInt()
private val C_BLUE = 0xFF0277BD.toInt()
private val C_GREEN = 0xFF00A152.toInt()
private val C_PURPLE = 0xFF6A1B9A.toInt()
private val C_CYAN = 0xFF00838F.toInt()
private val C_REC = 0xFFAD1457.toInt()
private val C_MACRO = 0xFF4527A0.toInt()
private val C_RED = 0xFFD50000.toInt()

class Toolbox(
    private val ctx: Context,
    private val wm: WindowManager,
    private val scope: CoroutineScope,
    private val ap: AssistPrefs,
    private val autoClicker: AutoClickerTarget,
    private val onClose: () -> Unit,
) {
    private val unit = DisplayMetrics.DENSITY_DEVICE_STABLE / 160f
    private fun dp(v: Int) = (v * unit).roundToInt()

    private var root: LinearLayout? = null
    private var bubble: TextView? = null
    private var scroll: ScrollView? = null
    private var panel: LinearLayout? = null
    private var stats: TextView? = null
    private var clickBtn: TextView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var panelW = 0
    private var lastLen = 0
    private var flashUntil = 0L
    private var dndOn = ap.dnd

    val shown: Boolean get() = root != null

    private fun rounded(color: Int, r: Int) = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat()
        setColor(color)
    }

    private fun label(s: String, size: Float, color: Int = Color.WHITE, bold: Boolean = true) = TextView(ctx).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * unit)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun button(title: String, color: Int, block: suspend (TextView) -> String): TextView =
        label(title, 13f).apply {
            background = rounded(color, 12)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnClickListener { v ->
                scope.launch { flash(runCatching { block(v as TextView) }.getOrElse { "ผิดพลาด: ${it.message}" }) }
            }
        }

    private fun row(vararg views: View) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(6)
            })
        }
    }

    private fun LinearLayout.addRow(v: View) = addView(
        v, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(6) }
    )

    private fun screen(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { wm.maximumWindowMetrics.bounds }.getOrNull()?.let { return it.width() to it.height() }
        }
        return legacyScreen()
    }

    @Suppress("DEPRECATION")
    private fun legacyScreen(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels to dm.heightPixels
    }

    private suspend fun captureHidden(): String {
        root?.visibility = View.INVISIBLE
        delay(250)
        val r = Tools.screenshot()
        root?.visibility = View.VISIBLE
        return if (r.ok) "บันทึกภาพแล้ว (Pictures/GameAssist)" else "ถ่ายภาพไม่สำเร็จ"
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null) {
            if (root?.isAttachedToWindow == true) return
            hide()   // root ค้างแต่ไม่ติดจอ (ระบบถอดออก) ล้างทิ้งแล้วสร้างใหม่
        }
        val p = Prefs(ctx)
        val (sw, sh) = screen()
        panelW = (minOf(sw, sh) * 0.62f).roundToInt().coerceAtLeast(dp(230))

        val b = label("GAME ASSIST", 13f).apply {
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            background = rounded(TB_NORMAL, 20)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        val st = label("--", 12f, 0xFFB0BEC5.toInt(), bold = false).apply { typeface = Typeface.MONOSPACE }
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }
        content.addView(label("GAME ASSIST TOOLBOX", 12f, 0xFFFFD600.toInt()))
        content.addRow(st)

        // ปุ่ม Auto-Clicker ในแผงลอย
        val cb = label("🎯 Auto-Click: ปิด", 13f).apply {
            background = rounded(C_CYAN, 12)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnClickListener {
                val run = autoClicker.toggle(scope)
                text = if (run) "🎯 Auto-Click: เปิด" else "🎯 Auto-Click: ปิด"
                background = rounded(if (run) C_GREEN else C_CYAN, 12)
            }
        }
        clickBtn = cb
        content.addRow(cb)

        content.addRow(row(
            button("DPI เกม ${p.gameDpi}", C_ORANGE) {
                GameDetectorService.manualDpi = true
                if (Tweaks.enterGameProfile(p).ok) "DPI = ${p.gameDpi}" else "เปลี่ยน DPI ไม่สำเร็จ"
            },
            button("DPI เดิม", C_BLUE) {
                GameDetectorService.manualDpi = true
                if (Tweaks.exitGameProfile(p).ok) "คืน DPI แล้ว" else "ไม่สำเร็จ"
            },
        ))
        content.addRow(row(
            button("RAM Boost", C_GREEN) { "เคลียร์ ${Tweaks.boostRam(p.gamePackages + ctx.packageName)} แอป" },
            button("ห้ามรบกวน", C_PURPLE) {
                dndOn = !dndOn
                if (GameMode.setDnd(dndOn).ok) "DND ${if (dndOn) "เปิด" else "ปิด"}" else "สั่ง DND ไม่สำเร็จ"
            },
        ))
        content.addRow(row(
            button("ภาพหน้าจอ", C_CYAN) { captureHidden() },
            button("อัดจอ", C_REC) { v ->
                if (Tools.isRecording()) {
                    Tools.stopRecord(); v.text = "อัดจอ"; "หยุดอัดแล้ว (Movies/GameAssist)"
                } else if (Tools.startRecord().ok) {
                    v.text = "หยุดอัด"; "เริ่มอัดจอ (สูงสุด 3 นาที)"
                } else "อัดจอไม่สำเร็จ"
            },
        ))

        val pinned = MacroStore(ctx).load().filter { it.pinned }.take(6)
        if (pinned.isNotEmpty()) {
            content.addRow(label("มาโคร", 12f, 0xFFB388FF.toInt()))
            pinned.chunked(2).forEach { pair ->
                content.addRow(row(*pair.map { m ->
                    button("▶ ${m.name}", C_MACRO) {
                        if (MacroEngine.run(ctx, m)) "มาโคร ${m.name} เสร็จ" else "มาโคร ${m.name} ล้มเหลว"
                    }
                }.toTypedArray()))
            }
        }

        content.addRow(button("รีเซ็ตฉุกเฉิน", C_RED) {
            if (Assist.fullReset(ctx).ok) "คืนค่าโรงงานแล้ว" else "รีเซ็ตไม่สำเร็จ"
        })
        content.addRow(row(
            label("ซ่อนแผง", 12f).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { togglePanel(false) }
            },
            label("ปิดผู้ช่วย", 12f, 0xFFFF8A80.toInt()).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(8), 0, dp(8))
                setOnClickListener { onClose() }
            },
        ))

        val sv = ScrollView(ctx).apply {
            background = rounded(0xF0101020.toInt(), 16)
            visibility = View.GONE
            addView(content, ViewGroup.LayoutParams(panelW, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val r = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(sv, LinearLayout.LayoutParams(panelW, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ap.tbX
            y = ap.tbY
            if (Build.VERSION.SDK_INT >= 28)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        b.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx
                    val dy = e.rawY - ty
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        params.x = sx + dx.toInt()
                        params.y = sy + dy.toInt()
                        runCatching { wm.updateViewLayout(r, params) }
                    }
                }
                MotionEvent.ACTION_UP -> if (moved) {
                    relayout(); ap.tbX = params.x; ap.tbY = params.y
                } else togglePanel(sv.visibility != View.VISIBLE)
            }
            true
        }

        try {
            wm.addView(r, params)
        } catch (e: Throwable) {
            GameDetectorService.lastDecision.value = "สร้างเมนูลอยไม่สำเร็จ: ${e.javaClass.simpleName} ${e.message?.take(80)}"
            throw e
        }
        root = r; bubble = b; scroll = sv; panel = content; stats = st; lp = params
        relayout()
        // ยืนยันว่าหน้าต่างติดอยู่บนจอจริง ไม่ใช่แค่โค้ดวิ่งผ่าน
        r.post {
            GameDetectorService.lastDecision.value =
                if (r.isAttachedToWindow && r.isShown) "เมนูขึ้นบนจอแล้วที่ (${params.x},${params.y}) ขนาด ${r.width}x${r.height}"
                else "เมนูถูกสร้างแต่ไม่แสดงบนจอ (attached=${r.isAttachedToWindow} shown=${r.isShown})"
        }
    }

    private fun togglePanel(open: Boolean) {
        scroll?.visibility = if (open) View.VISIBLE else View.GONE
        relayout()
    }

    fun relayout() {
        val r = root ?: return
        val sv = scroll ?: return
        val content = panel ?: return
        val params = lp ?: return
        val (sw, sh) = screen()
        val m = dp(8)
        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        if (sv.visibility == View.VISIBLE) {
            content.measure(View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY), unspec)
            val bubbleH = bubble?.let { it.measure(unspec, unspec); it.measuredHeight } ?: 0
            val maxH = (sh - bubbleH - m * 4).coerceAtLeast(dp(120))
            sv.layoutParams = (sv.layoutParams as LinearLayout.LayoutParams).apply {
                height = if (content.measuredHeight > maxH) maxH else LinearLayout.LayoutParams.WRAP_CONTENT
            }
        }
        r.measure(
            View.MeasureSpec.makeMeasureSpec(sw, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(sh, View.MeasureSpec.AT_MOST)
        )
        params.x = params.x.coerceIn(m, (sw - r.measuredWidth - m).coerceAtLeast(m))
        params.y = params.y.coerceIn(m, (sh - r.measuredHeight - m).coerceAtLeast(m))
        runCatching { wm.updateViewLayout(r, params) }
    }

    fun update(bubbleText: String, statsText: String, warn: Boolean) {
        stats?.text = statsText
        val b = bubble ?: return
        if (SystemClock.elapsedRealtime() < flashUntil) return
        b.text = bubbleText
        b.background = rounded(if (warn) TB_WARN else TB_NORMAL, 20)
        if (bubbleText.length != lastLen) { lastLen = bubbleText.length; relayout() }
        val run = autoClicker.isRunning
        clickBtn?.text = if (run) "🎯 Auto-Click: เปิด" else "🎯 Auto-Click: ปิด"
        clickBtn?.background = rounded(if (run) C_GREEN else C_CYAN, 12)
    }

    fun flash(msg: String, ms: Long = 2500) {
        val b = bubble ?: return
        flashUntil = SystemClock.elapsedRealtime() + ms
        b.text = msg
        b.background = rounded(TB_INFO, 20)
        lastLen = -1
        relayout()
    }

    /** หน้าต่างเมนูยังติดอยู่บนจอจริงไหม */
    fun isAlive(): Boolean = root?.isAttachedToWindow == true

    fun hide() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null; bubble = null; scroll = null; panel = null; stats = null; clickBtn = null; lp = null
    }
}

// ============================ DETECTOR + ASSIST SESSION ============================
class GameDetectorService : AccessibilityService() {
    companion object {
        @Volatile var instance: GameDetectorService? = null
            private set
        @Volatile var manualDpi = false
        val live = MutableStateFlow(false)
        val gameRunning = MutableStateFlow<String?>(null)
        /** เหตุผลล่าสุดที่ตัดสินใจ เช่น "เห็นแอป com.x: ไม่อยู่ในรายชื่อเกม" ไว้แสดงในแอป */
        val lastDecision = MutableStateFlow("ยังไม่มีอีเวนต์จาก Accessibility (ลองสลับไปเปิดแอปอื่นแล้วกลับ)")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private lateinit var prefs: Prefs
    private lateinit var ap: AssistPrefs
    private lateinit var macros: MacroStore
    private var pending: Job? = null
    @Volatile private var sessionJob: Job? = null
    @Volatile private var userClosed = false
    @Volatile private var lastEventPkg: String? = null

    private val autoClicker by lazy { AutoClickerTarget(this, getSystemService(WINDOW_SERVICE) as WindowManager, ap, this) }

    private var tb: Toolbox? = null
    private val toolbox: Toolbox
        get() = tb ?: Toolbox(this, getSystemService(WINDOW_SERVICE) as WindowManager, uiScope, ap, autoClicker) {
            userClosed = true
            stopSession()
        }.also { tb = it }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        ap = AssistPrefs(this)
        macros = MacroStore(this)
        instance = this
        live.value = true
        scope.launch { Assist.ensureShell() }
        if (prefs.inGameProfile || ap.sessionActive) schedule(null, 1500)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        uiScope.launch { delay(300); tb?.relayout() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || !::prefs.isInitialized) return
        val p = event.packageName?.toString() ?: return
        // อีเวนต์จากหน้าต่างของระบบ/คีย์บอร์ด/ตัวเราเอง ไม่ใช่การสลับแอป ข้ามไป
        if (p == packageName || p == "com.android.systemui" || p.contains("inputmethod")) return
        lastEventPkg = p
        if (!prefs.autoApply && !prefs.inGameProfile && !ap.enabled && !ap.sessionActive) {
            lastDecision.value = "เห็นแอป $p แต่ปิดผู้ช่วยอัตโนมัติอยู่"
            return
        }
        schedule(p, 400)
    }

    private fun schedule(pkg: String?, delayMs: Long) {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            withContext(NonCancellable) { mutex.withLock { evaluate(pkg) } }
        }
    }

    private suspend fun evaluate(pkg: String?) {
        val games = prefs.gamePackages
        val resumed = runCatching { Tweaks.resumedPackages() }.getOrDefault(emptySet())
        // โหมดทุกแอป: แอปใดก็ได้ที่ไม่ใช่ตัวเอง/launcher/SystemUI นับเป็นเกม
        fun isGame(p: String) = p in games || (prefs.allApps && !Assist.isIgnorable(this, p))
        val seen = pkg ?: lastEventPkg
        // หน้าต่างชั่วคราวของระบบ (แจ้งเตือน, แผงลัด, คีย์บอร์ด, ตัวเลือกแชร์) ไม่ถือว่าออกจากเกม
        if (seen != null && ap.sessionActive && !isGame(seen) && Assist.isTransient(this, seen)) {
            lastDecision.value = "เห็นหน้าต่างระบบ $seen ชั่วคราว ไม่นับว่าออกจากเกม"
            return
        }
        // ถ้า dumpsys ใช้ไม่ได้ (Shizuku ยังไม่พร้อม) ใช้แพ็กเกจจากอีเวนต์แทน เมนูจะได้ขึ้นอยู่ดี
        val game: String? = when {
            resumed.isNotEmpty() -> resumed.firstOrNull { isGame(it) }
            seen != null && isGame(seen) -> seen
            seen != null -> null
            else -> return
        }
        val inGame = game != null
        gameRunning.value = game
        if (game != null) prefs.lastGame = game
        lastDecision.value = when {
            inGame -> "เจอเกม $game → เปิดเมนู"
            seen != null -> "เห็นแอป $seen: ไม่อยู่ในรายชื่อเกม (กด \"เพิ่มเกมที่เปิดอยู่\" หรือเปิดโหมดทุกแอป)"
            else -> "ไม่รู้ว่าแอปไหนอยู่หน้าจอ"
        }

        // เมนูต้องขึ้นก่อนเสมอ งานที่ต้องใช้ Shizuku (DPI, DND, มาโคร) ทำทีหลังและห้ามทำให้เมนูไม่ขึ้น
        if (inGame && ap.enabled && !userClosed && sessionJob?.isActive != true) {
            startSession(game, forceToolbox = false)
        }

        runCatching {
            when {
                inGame && !prefs.inGameProfile && prefs.autoApply && !manualDpi -> Tweaks.enterGameProfile(prefs)
                !inGame && prefs.inGameProfile -> Tweaks.exitGameProfile(prefs)
            }
        }
        if (!inGame) manualDpi = false

        when {
            inGame && ap.enabled && !ap.sessionActive -> {
                ap.sessionActive = true
                userClosed = false
                startSession(game, forceToolbox = false)
                // งานเสริมแต่ละอย่างแยกกัน อันไหนล้มไม่กระทบอันอื่น และไม่กระทบการเปิดเมนู
                if (ap.boostOnStart) runCatching { Tweaks.boostRam(games + packageName) }
                runCatching { GameMode.enter(ap, game) }
                // มาโครรันแยก coroutine: ช้า/ล้ม/ไม่มีมาโครก็ไม่หน่วงหรือหยุดสิ่งอื่น
                // และไม่เปิดเกมอื่นแทนเกมที่ผู้ใช้กำลังเล่น (ข้ามถ้าไม่ได้เลือกมาโคร)
                val startId = ap.startMacroId
                if (startId != null) scope.launch {
                    val m = macros.find(startId)
                    if (m == null) ap.startMacroId = null   // มาโครถูกลบแล้ว ล้างการอ้างอิงค้าง
                    else runCatching { MacroEngine.run(this@GameDetectorService, m) }
                }
            }
            inGame && ap.enabled && ap.sessionActive && !userClosed && sessionJob?.isActive != true ->
                startSession(game, forceToolbox = false)
            !inGame && ap.sessionActive -> {
                delay(1200)
                val again = runCatching { Tweaks.resumedPackages() }.getOrDefault(emptySet())
                val stillGame = again.any { isGame(it) } ||
                    (again.isEmpty() && lastEventPkg?.let { isGame(it) } == true)
                if (stillGame) {
                    lastDecision.value = "ยังอยู่ในเกม (ยืนยันซ้ำ) เก็บเมนูไว้"
                    return
                }
                stopSession()
                runCatching { GameMode.exit(ap) }
                ap.sessionActive = false
                userClosed = false
                val exitId = ap.exitMacroId
                if (exitId != null) scope.launch {
                    val m = macros.find(exitId)
                    if (m == null) ap.exitMacroId = null
                    else runCatching { MacroEngine.run(this@GameDetectorService, m) }
                }
            }
        }
    }

    fun startSession(game: String? = gameRunning.value, forceToolbox: Boolean = true) {
        uiScope.launch {
            if (sessionJob?.isActive == true) {
                if (forceToolbox) {
                    runCatching { toolbox.show() }.onFailure { lastDecision.value = "เปิดเมนูล้มเหลว: ${it.javaClass.simpleName} ${it.message?.take(80)}" }
                    runCatching { autoClicker.show() }
                }
                return@launch
            }
            sessionJob = uiScope.launch { runSession(game, forceToolbox) }
        }
    }

    fun stopSession() {
        uiScope.launch {
            sessionJob?.cancel()
            sessionJob = null
            tb?.hide()
            autoClicker.hide()
        }
    }

    private suspend fun runSession(game: String?, forceToolbox: Boolean) = coroutineScope {
        if (ap.toolbox || forceToolbox) {
            runCatching { toolbox.show() }.onFailure { lastDecision.value = "เปิดเมนูล้มเหลว: ${it.javaClass.simpleName} ${it.message?.take(80)}" }
            runCatching { autoClicker.show() }
        }
        val ctx = this@GameDetectorService
        val t0 = SystemClock.elapsedRealtime()
        val startedAt = System.currentTimeMillis()
        val breakMs = ap.breakMin * 60_000L
        var nextBreak = breakMs
        var bat = Assist.battery(ctx)
        val startBat = bat.pct
        var minBat = bat.pct
        var maxTemp = bat.tempC ?: 0f
        var tick = 0
        var hotWarned = false
        var lowWarned = false
        var ping: Int? = null
        var pingSum = 0L
        var pingN = 0
        var pingMax = 0
        var pingBad = 0
        var lastPingAlert = 0L
        var fps: Int? = null
        var fpsSum = 0L
        var fpsN = 0

        launch(Dispatchers.IO) {
            while (isActive) {
                val v = Metrics.tcpPing()
                withContext(Dispatchers.Main) {
                    ping = v
                    if (v != null) { pingSum += v; pingN++; if (v > pingMax) pingMax = v }
                    pingBad = if (v == null || v >= ap.pingAlertMs) pingBad + 1 else 0
                }
                delay(3000)
            }
        }
        if (ap.toolboxFps) launch(Dispatchers.IO) {
            var last: Long? = null
            var lastT = 0L
            while (isActive) {
                val c = Metrics.sfFrameCount()
                val now = SystemClock.elapsedRealtime()
                val prev = last
                val f = if (c != null && prev != null && now > lastT)
                    ((c - prev) * 1000.0 / (now - lastT)).roundToInt().coerceIn(0, 240) else null
                if (c != null) { last = c; lastT = now }
                withContext(Dispatchers.Main) { fps = f; if (f != null && f > 0) { fpsSum += f; fpsN++ } }
                delay(2000)
            }
        }

        try {
            while (isActive) {
                if (++tick % 5 == 0) {
                    bat = Assist.battery(ctx)
                    bat.tempC?.let { if (it > maxTemp) maxTemp = it }
                    if (bat.pct in 0 until minBat) minBat = bat.pct
                }
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - t0
                val t = bat.tempC
                val hot = t != null && t >= ap.heatAlertC
                val low = !bat.charging && bat.pct in 0..ap.batteryAlert
                val lag = pingBad >= 3

                if (hot && !hotWarned) { alert("เครื่องร้อน ${"%.1f".format(t ?: 0f)}°C ลองลดกราฟิกหรือพักเครื่อง"); hotWarned = true }
                if (t != null && t < ap.heatAlertC - 2) hotWarned = false
                if (low && !lowWarned) { alert("แบตเหลือ ${bat.pct}% เสียบชาร์จก่อนแบตหมดกลางเกม"); lowWarned = true }
                if (bat.charging) lowWarned = false
                if (lag && now - lastPingAlert > 60_000) {
                    alert(if (ping == null) "เน็ตไม่ตอบสนอง ตรวจ Wi-Fi/เน็ตมือถือ" else "Ping สูง ${ping}ms เน็ตอาจแลค")
                    lastPingAlert = now
                }
                if (breakMs > 0 && elapsed >= nextBreak) {
                    alert("เล่นมา ${elapsed / 60_000} นาทีแล้ว พักสายตาสักครู่", 10_000)
                    nextBreak += breakMs
                }

                val tempS = t?.let { "%.0f°C".format(it) } ?: "--°C"
                val batS = if (bat.pct >= 0) "${bat.pct}%" else "--%"
                val pingS = ping?.let { "${it}ms" } ?: "--"
                val fpsS = fps?.toString() ?: "--"
                val avgPing = if (pingN > 0) pingSum / pingN else 0
                val bubbleText = if (ap.toolboxFps) "${Assist.fmt(elapsed)} | FPS $fpsS | $pingS"
                else "${Assist.fmt(elapsed)} | $tempS | $batS"
                val statsText = "เวลาเล่น ${Assist.fmt(elapsed)}\n" +
                    "FPS $fpsS  |  Ping $pingS (เฉลี่ย ${avgPing}ms)\n" +
                    "แบต $batS${if (bat.charging) " (ชาร์จ)" else ""}  |  $tempS"
                if (ap.toolbox && !userClosed && tb?.isAlive() != true) {
                    runCatching { toolbox.show() }
                        .onFailure { lastDecision.value = "สร้างเมนูใหม่ไม่สำเร็จ: ${it.javaClass.simpleName} ${it.message?.take(80)}" }
                }
                tb?.update(bubbleText, statsText, hot || low || lag)
                delay(1000)
            }
        } finally {
            tb?.hide()
            autoClicker.hide()
            val dur = SystemClock.elapsedRealtime() - t0
            if (game != null && dur >= 60_000) {
                ap.addHistory(
                    SessionRecord(
                        game, startedAt, dur,
                        if (fpsN > 0) (fpsSum / fpsN).toInt() else 0,
                        if (pingN > 0) (pingSum / pingN).toInt() else 0,
                        pingMax, maxTemp,
                        if (startBat >= 0 && minBat >= 0) (startBat - minBat).coerceAtLeast(0) else 0
                    )
                )
            }
        }
    }

    private fun alert(msg: String, ms: Long = 4000) {
        val t = tb
        if (t != null && t.shown) t.flash(msg, ms) else Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        live.value = false
        gameRunning.value = nilSafeGame()
        manualDpi = false
        sessionJob?.cancel()
        runCatching { tb?.hide() }
        runCatching { autoClicker.hide() }
        if (::prefs.isInitialized) {
            val p = prefs
            val a = ap
            CoroutineScope(Dispatchers.IO).launch {
                withTimeoutOrNull(5000) {
                    if (p.inGameProfile) Tweaks.exitGameProfile(p)
                    if (a.sessionActive) { GameMode.exit(a); a.sessionActive = false }
                }
            }
        }
        scope.cancel()
        uiScope.cancel()
        super.onDestroy()
    }

    private fun nilSafeGame(): String? = null
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
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            val ok = Assist.ensureShell() != Shell.Mode.NONE && Assist.fullReset(app).ok
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
