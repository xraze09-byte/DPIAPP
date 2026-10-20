package com.dpi.gamingtweaker

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.SystemClock
import android.view.Display
import androidx.core.content.edit
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

// ============================ PREFS ============================
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("dpi_tweaker", Context.MODE_PRIVATE)

    var gameDpi: Int
        get() = sp.getInt("game_dpi", 600)
        set(v) = sp.edit { putInt("game_dpi", v) }

    var autoApply: Boolean
        get() = sp.getBoolean("auto_apply", false)
        set(v) = sp.edit { putBoolean("auto_apply", v) }

    var gamePackages: Set<String>
        get() = sp.getStringSet("game_pkgs", null)?.toSet() ?: DEFAULT_GAMES
        set(v) = sp.edit { putStringSet("game_pkgs", v) }

    // สถานะสำคัญ: commit = true เขียนลงดิสก์ทันที
    var preGameDpi: Int
        get() = sp.getInt("pre_game_dpi", -1)
        set(v) = sp.edit(commit = true) { putInt("pre_game_dpi", v) }

    var inGameProfile: Boolean
        get() = sp.getBoolean("in_game", false)
        set(v) = sp.edit(commit = true) { putBoolean("in_game", v) }

    /** คำสั่งคืนค่า refresh rate เดิม (null = ไม่ได้ล็อกอยู่) */
    var refreshRestore: String?
        get() = sp.getString("refresh_restore", null)
        set(v) = sp.edit(commit = true) { putString("refresh_restore", v) }

    /** คำสั่งคืนค่าแอนิเมชัน/long-press เดิม (null = Touch Boost ปิดอยู่) */
    var touchRestore: String?
        get() = sp.getString("touch_restore", null)
        set(v) = sp.edit(commit = true) { putString("touch_restore", v) }

    companion object {
        val DEFAULT_GAMES = setOf("com.dts.freefireth", "com.dts.freefiremax")
    }
}

// ============================ SHELL ============================
object Shell {
    enum class Mode { NONE, SHIZUKU, ROOT }

    data class Result(val code: Int, val out: String, val err: String) {
        val ok get() = code == 0
    }

    const val SHIZUKU_REQ = 1001
    private const val DETECT_COOLDOWN_MS = 5_000L

    @Volatile var mode: Mode = Mode.NONE
        private set
    @Volatile private var lastDetect = 0L

    private val readerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Shizuku 13.x ทำ newProcess เป็น private จึงต้องเรียกผ่าน reflection
    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        ).apply { isAccessible = true }
    }

    fun shizukuRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun shizukuGranted(): Boolean = runCatching {
        shizukuRunning() && !Shizuku.isPreV11() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    private fun rootAvailable(): Boolean {
        val p = runCatching { Runtime.getRuntime().exec(arrayOf("su", "-c", "id")) }.getOrNull() ?: return false
        return try {
            p.waitFor(10, TimeUnit.SECONDS) && p.inputStream.bufferedReader().readText().contains("uid=0")
        } catch (_: Exception) {
            false
        } finally {
            runCatching { p.destroy() }
        }
    }

    @Synchronized
    fun detect(force: Boolean = true): Mode {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastDetect < DETECT_COOLDOWN_MS) return mode
        lastDetect = now
        mode = when {
            shizukuGranted() -> Mode.SHIZUKU
            rootAvailable() -> Mode.ROOT
            else -> Mode.NONE
        }
        return mode
    }

    private fun spawn(cmd: String): Process = when (mode) {
        Mode.SHIZUKU -> newProcess.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        Mode.ROOT -> Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        Mode.NONE -> error("No Shizuku/Root")
    }

    /** null = ยังทำงานอยู่ (Shizuku ส่ง exception ข้าม Binder มาเป็น IllegalArgumentException) */
    private fun exitCodeOrNull(p: Process): Int? = try {
        p.exitValue()
    } catch (_: IllegalThreadStateException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    suspend fun exec(cmd: String, timeoutSec: Long = 15): Result = withContext(Dispatchers.IO) {
        if (mode == Mode.NONE || (mode == Mode.SHIZUKU && !shizukuRunning())) detect(force = false)
        if (mode == Mode.NONE) return@withContext Result(-1, "", "ไม่มีสิทธิ์ Shizuku/Root")

        val p = try {
            spawn(cmd)
        } catch (e: Throwable) {
            return@withContext Result(-1, "", e.cause?.message ?: e.message ?: e.javaClass.simpleName)
        }

        try {
            val out = readerScope.async { runCatching { p.inputStream.bufferedReader().readText() }.getOrDefault("") }
            val err = readerScope.async { runCatching { p.errorStream.bufferedReader().readText() }.getOrDefault("") }
            val code = withTimeoutOrNull(timeoutSec * 1000) {
                var c = exitCodeOrNull(p)
                while (c == null) { delay(15); c = exitCodeOrNull(p) }
                c
            }
            if (code == null) runCatching { p.destroy() }
            val o = withTimeoutOrNull(2000) { out.await() } ?: ""
            val e = withTimeoutOrNull(2000) { err.await() } ?: ""
            Result(code ?: -2, o.trim(), e.trim())
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Result(-1, "", t.message ?: t.javaClass.simpleName)
        } finally {
            runCatching { p.destroy() }
        }
    }
}

// ============================ TWEAKS ============================
object Tweaks {
    data class DisplayValue<T>(val physical: T?, val override: T?) {
        val active: T? get() = override ?: physical
    }

    data class FailSafeSession(val flag: String, val revertCmd: String, val label: String)

    private const val TMP = "/data/local/tmp"
    private const val WATCHDOG_GRACE_SEC = 3
    private val RESUMED_RE = Regex("""u\d+\s+([a-zA-Z][\w.]*)/""")
    private val NUM_RE = Regex("""-?\d+(\.\d+)?""")
    val PKG_RE = Regex("""[a-zA-Z]\w*(\.[a-zA-Z_]\w*)+""")
    private val OK = Shell.Result(0, "", "")

    private val REFRESH_KEYS = listOf("system" to "peak_refresh_rate", "system" to "min_refresh_rate")
    private val TOUCH_KEYS = listOf(
        "global" to "window_animation_scale",
        "global" to "transition_animation_scale",
        "global" to "animator_duration_scale",
        "secure" to "long_press_timeout",
    )

    // ---------- อ่านค่า ----------
    fun parseWmDensity(out: String) = DisplayValue(
        Regex("""Physical density:\s*(\d+)""").find(out)?.groupValues?.get(1)?.toIntOrNull(),
        Regex("""Override density:\s*(\d+)""").find(out)?.groupValues?.get(1)?.toIntOrNull(),
    )

    fun parseWmSize(out: String) = DisplayValue(
        Regex("""Physical size:\s*(\d+x\d+)""").find(out)?.groupValues?.get(1),
        Regex("""Override size:\s*(\d+x\d+)""").find(out)?.groupValues?.get(1),
    )

    suspend fun readDensity(): DisplayValue<Int>? =
        Shell.exec("wm density").takeIf { it.ok }?.let { parseWmDensity(it.out) }

    suspend fun readSize(): DisplayValue<String>? =
        Shell.exec("wm size").takeIf { it.ok }?.let { parseWmSize(it.out) }

    /** อ่านค่า setting ปัจจุบันแล้วสร้างคำสั่งที่คืนค่าเดิมได้ตรงตามเดิม */
    private suspend fun snapshot(keys: List<Pair<String, String>>): String? {
        val r = Shell.exec(keys.joinToString("; ") { (ns, k) -> "echo \"$k=\$(settings get $ns $k)\"" })
        if (!r.ok) return null
        val values = r.out.lines().associate { it.substringBefore('=') to it.substringAfter('=', "").trim() }
        return keys.joinToString("") { (ns, k) ->
            val v = values[k]
            if (v != null && NUM_RE.matches(v)) "settings put $ns $k $v; " else "settings delete $ns $k; "
        }
    }

    // ---------- Fail-Safe 2 ชั้น ----------
    suspend fun applyWithFailSafe(dpi: Int?, size: String?, seconds: Int, label: String): FailSafeSession? {
        if (dpi == null && size == null) return null
        val curD = if (dpi != null) readDensity() else null
        val curS = if (size != null) readSize() else null

        val revert = buildString {
            if (size != null) append(curS?.override?.let { "wm size $it; " } ?: "wm size reset; ")
            if (dpi != null) append(curD?.override?.let { "wm density $it; " } ?: "wm density reset; ")
        }
        val apply = buildString {
            if (size != null) append("wm size $size; ")
            if (dpi != null) append("wm density $dpi; ")
        }
        val flag = "$TMP/.dpi_ok_${System.currentTimeMillis()}"
        val wait = seconds + WATCHDOG_GRACE_SEC
        val watchdog = "nohup sh -c 'sleep $wait; [ -f $flag ] || { $revert}; rm -f $flag' >/dev/null 2>&1 &"

        val r = Shell.exec("$watchdog $apply")
        if (!r.ok) {
            Shell.exec("touch $flag; $revert")
            return null
        }
        return FailSafeSession(flag, revert, label)
    }

    suspend fun confirm(s: FailSafeSession) = Shell.exec("touch ${s.flag}")
    suspend fun revert(s: FailSafeSession) = Shell.exec("touch ${s.flag}; ${s.revertCmd}")

    // ---------- Optimizer ----------
    suspend fun setPointerSpeed(v: Int) =
        Shell.exec("settings put system pointer_speed ${v.coerceIn(-7, 7)}")

    suspend fun touchBoost(on: Boolean, p: Prefs): Shell.Result {
        if (on) {
            if (p.touchRestore == null) {
                p.touchRestore = snapshot(TOUCH_KEYS) ?: return Shell.Result(-1, "", "อ่านค่าเดิมไม่ได้")
            }
            return Shell.exec(
                "settings put global window_animation_scale 0.5; " +
                    "settings put global transition_animation_scale 0.5; " +
                    "settings put global animator_duration_scale 0.5; " +
                    "settings put secure long_press_timeout 250"
            )
        }
        val cmd = p.touchRestore ?: return OK
        return Shell.exec(cmd).also { if (it.ok) p.touchRestore = null }
    }

    suspend fun lockRefreshRate(hz: Int?, p: Prefs): Shell.Result {
        if (hz != null) {
            if (p.refreshRestore == null) {
                p.refreshRestore = snapshot(REFRESH_KEYS) ?: return Shell.Result(-1, "", "อ่านค่าเดิมไม่ได้")
            }
            return Shell.exec("settings put system peak_refresh_rate $hz.0; settings put system min_refresh_rate $hz.0")
        }
        val cmd = p.refreshRestore ?: return OK
        return Shell.exec(cmd).also { if (it.ok) p.refreshRestore = null }
    }

    suspend fun emergencyReset(p: Prefs): Shell.Result {
        val cmd = buildString {
            // ใช้ dpi_o[k]_ เพื่อไม่ให้ pkill ไปเจอ command line ของตัวเอง
            append("pkill -f 'dpi_o[k]_' 2>/dev/null; rm -f $TMP/.dpi_o[k]_* 2>/dev/null; ")
            append("settings put system pointer_speed 0; ")
            append(p.touchRestore ?: "")
            append(p.refreshRestore ?: "")
            append("wm size reset; wm density reset")
        }
        val r = Shell.exec(cmd)
        if (r.ok) {
            p.touchRestore = null
            p.refreshRestore = null
            p.inGameProfile = false
            p.preGameDpi = -1
        }
        return r
    }

    /** RAM Booster: ข้าม Launcher, คีย์บอร์ด, Accessibility, Wallpaper และเกม */
    suspend fun boostRam(keep: Set<String>): Int {
        val ime = Shell.exec("settings get secure default_input_method").out.substringBefore('/')
        val launcher = Shell.exec(
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1"
        ).out.trim().substringBefore('/')
        val a11y = Shell.exec("settings get secure enabled_accessibility_services").out
            .split(':').map { it.substringBefore('/').trim() }
        val wallpaper = Shell.exec("dumpsys wallpaper | grep -m1 'mWallpaperComponent'").out
            .substringAfter('{', "").substringBefore('/').trim()

        val protected = keep + a11y + setOf(
            ime, launcher, wallpaper, "moe.shizuku.privileged.api", "com.topjohnwu.magisk"
        )
        val targets = Shell.exec("pm list packages -3").out.lineSequence()
            .map { it.removePrefix("package:").trim() }
            .filter { PKG_RE.matches(it) && it !in protected }
            .toList()
        targets.chunked(40).forEach { batch ->
            Shell.exec(batch.joinToString("; ") { "am kill $it" }, timeoutSec = 30)
        }
        return targets.size
    }

    // ---------- Auto-Apply / Game ----------
    suspend fun resumedPackages(): Set<String> {
        val r = Shell.exec("dumpsys activity activities | grep -E 'ResumedActivity' | grep -vi 'last'", 5)
        return RESUMED_RE.findAll(r.out).map { it.groupValues[1] }.toSet()
    }

    suspend fun enterGameProfile(p: Prefs): Shell.Result {
        if (!p.inGameProfile) {
            val cur = readDensity() ?: return Shell.Result(-1, "", "อ่าน DPI ไม่ได้")
            p.preGameDpi = cur.override ?: -1
            p.inGameProfile = true
        }
        return Shell.exec("wm density ${p.gameDpi}")
    }

    suspend fun exitGameProfile(p: Prefs): Shell.Result {
        val r = Shell.exec(if (p.preGameDpi > 0) "wm density ${p.preGameDpi}" else "wm density reset")
        if (r.ok) {
            p.inGameProfile = false
            p.preGameDpi = -1
        }
        return r
    }

    /** ตั้ง DPI ก่อนแล้วค่อยเปิดเกม → เกม Unity ไม่ต้องรีโหลดตัวเอง */
    suspend fun launchGame(p: Prefs, pkg: String): Shell.Result {
        if (!PKG_RE.matches(pkg)) return Shell.Result(-1, "", "package ไม่ถูกต้อง")
        val r = enterGameProfile(p)
        if (!r.ok) return r
        delay(400)
        val l = Shell.exec("monkey -p $pkg -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
        if (!l.ok) {
            exitGameProfile(p)
            return Shell.Result(l.code, "", "ไม่พบเกม $pkg ในเครื่อง")
        }
        return l
    }

    // ---------- Self-grant ----------
    suspend fun selfGrant(pkg: String) = Shell.exec(
        "pm grant $pkg android.permission.POST_NOTIFICATIONS 2>/dev/null; " +
            "appops set $pkg SYSTEM_ALERT_WINDOW allow; " +
            "pm grant $pkg android.permission.WRITE_SECURE_SETTINGS"
    )

    suspend fun enableAccessibility(component: String): Shell.Result {
        val cur = Shell.exec("settings get secure enabled_accessibility_services").out
        val list = cur.split(':').filter { it.isNotBlank() && it != "null" }.toMutableList()
        if (component !in list) list += component
        return Shell.exec(
            "settings put secure enabled_accessibility_services '${list.joinToString(":")}'; " +
                "settings put secure accessibility_enabled 1"
        )
    }
}

// ============================ METRICS ============================
object Metrics {
    data class Telemetry(
        val activeDpi: Int? = null,
        val physicalDpi: Int? = null,
        val resolution: String = "--",
        val physicalRes: String? = null,
        val ramTotalMb: Long = 0,
        val ramAvailMb: Long = 0,
        val cpuTempC: Float? = null,
        val tempSource: String = "CPU",
        val pingMs: Int? = null,
        val refreshHz: Float = 0f,
    )

    private val CPU_ZONE = Regex("(?i)cpu|tsens|soc|apc|cluster|mtktscpu|little|big")
    private const val MARK = "===THERMAL==="

    // คำสั่งเดียวต่อรอบ (เดิม 3 คำสั่ง) → ไม่หน่วง และ Magisk ไม่ขึ้น toast รัว
    private const val TELEMETRY_CMD = "wm density; wm size; echo $MARK; " +
        "for z in /sys/class/thermal/thermal_zone*; do " +
        "echo \"\$(cat \$z/type 2>/dev/null)=\$(cat \$z/temp 2>/dev/null)\"; done"

    suspend fun collect(ctx: Context, hasShell: Boolean): Telemetry = coroutineScope {
        val ping = async(Dispatchers.IO) { tcpPing() }
        val out = if (hasShell) Shell.exec(TELEMETRY_CMD, 6).out else ""
        val d = Tweaks.parseWmDensity(out)
        val s = Tweaks.parseWmSize(out)
        val display = ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        val mode = display?.mode
        val (total, avail) = ram(ctx)
        val cpu = cpuTemp(out.substringAfter(MARK, ""))
        Telemetry(
            activeDpi = d.active ?: ctx.resources.displayMetrics.densityDpi,
            physicalDpi = d.physical,
            resolution = s.active ?: mode?.let { "${it.physicalWidth}x${it.physicalHeight}" } ?: "--",
            physicalRes = s.physical,
            ramTotalMb = total,
            ramAvailMb = avail,
            cpuTempC = cpu ?: batteryTemp(ctx),
            tempSource = if (cpu != null) "CPU" else "Battery",
            pingMs = ping.await(),
            refreshHz = display?.refreshRate ?: 0f,
        )
    }

    fun ram(ctx: Context): Pair<Long, Long> {
        val mi = ActivityManager.MemoryInfo()
        ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        return mi.totalMem / 1_048_576 to mi.availMem / 1_048_576
    }

    private fun cpuTemp(text: String): Float? = text.lineSequence().mapNotNull { line ->
        val type = line.substringBefore('=', "")
        val raw = line.substringAfter('=', "").trim().toLongOrNull() ?: return@mapNotNull null
        if (!CPU_ZONE.containsMatchIn(type)) return@mapNotNull null
        val c = if (raw > 1000) raw / 1000f else raw.toFloat()
        c.takeIf { it in 15f..125f }
    }.maxOrNull()

    private fun batteryTemp(ctx: Context): Float? = runCatching {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (t == Int.MIN_VALUE) null else t / 10f
    }.getOrNull()

    fun tcpPing(host: String = "8.8.8.8", port: Int = 53, timeoutMs: Int = 1500): Int? = runCatching {
        Socket().use { s ->
            val t0 = SystemClock.elapsedRealtimeNanos()
            s.connect(InetSocketAddress(host, port), timeoutMs)
            ((SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000).toInt()
        }
    }.getOrNull()

    suspend fun sfFrameCount(): Long? {
        val r = Shell.exec("service call SurfaceFlinger 1013", 3)
        return Regex("""Parcel\(([0-9a-fA-F]{8})""").find(r.out)?.groupValues?.get(1)?.toLongOrNull(16)
    }
}
