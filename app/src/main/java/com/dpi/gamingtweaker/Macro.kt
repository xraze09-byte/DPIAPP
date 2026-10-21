package com.dpi.gamingtweaker

import android.content.Context
import android.widget.Toast
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** มาโครระดับระบบ: รวมหลายการตั้งค่าไว้ในปุ่มเดียว (ไม่ได้จำลองการแตะในเกม) */
data class Macro(val id: String, val name: String, val script: String, val pinned: Boolean = false)

class MacroStore(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("macros", Context.MODE_PRIVATE)

    fun load(): List<Macro> {
        val raw = sp.getString("list", null) ?: return DEFAULTS
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                Macro(o.getString("id"), o.getString("name"), o.getString("script"), o.optBoolean("pinned", false))
            }
        }.getOrDefault(DEFAULTS)
    }

    fun save(list: List<Macro>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(JSONObject().put("id", m.id).put("name", m.name).put("script", m.script).put("pinned", m.pinned))
        }
        sp.edit { putString("list", arr.toString()) }
    }

    fun find(id: String?): Macro? = if (id == null) null else load().firstOrNull { it.id == id }

    companion object {
        val DEFAULTS = listOf(
            Macro(
                "prep_ff", "เตรียมเข้า Free Fire",
                "# เคลียร์แรม > ตั้งค่าเกม > เปิดเกม\nboost\ndnd on\ntouch on\nhz 90\ndpi game\nwait 500\nlaunch com.dts.freefireth"
            ),
            Macro("after_game", "คืนค่าหลังเล่น", "dpi reset\ntouch off\nhz reset\ndnd off", pinned = true),
            Macro("cool", "โหมดเย็นเครื่อง", "hz 60\nboost\ntoast เปิดโหมดเย็นเครื่องแล้ว", pinned = true),
            Macro("shot", "ถ่ายภาพหน้าจอ", "screenshot", pinned = true),
        )
    }
}

object MacroEngine {
    data class Step(val line: Int, val cmd: String, val arg: String)
    data class Parsed(val steps: List<Step>, val errors: List<String>)

    val running = MutableStateFlow<String?>(null)
    private val mutex = Mutex()
    private val OK = Shell.Result(0, "", "")

    val COMMANDS = listOf(
        "dpi 600" to "ตั้ง DPI 120–1200 | dpi game = DPI เกม | dpi reset = ค่าโรงงาน",
        "size 1080x2400" to "ตั้งความละเอียด | size reset = ค่าจริง",
        "hz 90" to "ล็อก refresh rate | hz reset = คืนค่าเดิม",
        "touch on" to "Touch Boost on/off",
        "pointer 3" to "Pointer speed -7 ถึง 7",
        "dnd on" to "ห้ามรบกวน on/off",
        "headsup off" to "แจ้งเตือนเด้งบังจอ on/off",
        "rotate lock" to "ล็อกการหมุนจอ lock/auto",
        "boost" to "RAM Booster",
        "launch com.dts.freefireth" to "เปิดแอปหรือเกม",
        "stop com.example.app" to "บังคับหยุดแอป",
        "wait 500" to "รอ (มิลลิวินาที)",
        "screenshot" to "ถ่ายภาพหน้าจอ",
        "toast สวัสดี" to "แสดงข้อความ",
    )

    fun parse(script: String): Parsed {
        val steps = mutableListOf<Step>()
        val errors = mutableListOf<String>()
        script.lines().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val cmd = line.substringBefore(' ').lowercase()
            val arg = line.substringAfter(' ', "").trim()
            val err = validate(cmd, arg)
            if (err == null) steps += Step(i + 1, cmd, arg) else errors += "บรรทัด ${i + 1}: $err"
        }
        if (steps.size > 60) errors += "มาโครยาวเกิน 60 คำสั่ง"
        return Parsed(steps, errors)
    }

    private fun intIn(a: String, r: IntRange) = a.toIntOrNull()?.let { it in r } == true

    // ตรวจทุกค่าก่อนส่งเข้า shell → กัน shell injection
    private fun validate(cmd: String, a: String): String? = when (cmd) {
        "dpi" -> if (a == "game" || a == "reset" || intIn(a, 120..1200)) null else "dpi ต้องเป็น 120–1200, game หรือ reset"
        "size" -> if (a == "reset" || MainViewModel.parseSize(a) != null) null else "size ต้องเป็นเช่น 1080x2400 หรือ reset"
        "hz" -> if (a == "reset" || intIn(a, 30..165)) null else "hz ต้องเป็น 30–165 หรือ reset"
        "touch", "dnd", "headsup" -> if (a == "on" || a == "off") null else "$cmd ต้องตามด้วย on หรือ off"
        "rotate" -> if (a == "lock" || a == "auto") null else "rotate ต้องเป็น lock หรือ auto"
        "pointer" -> if (intIn(a, -7..7)) null else "pointer ต้องเป็น -7 ถึง 7"
        "wait" -> if (intIn(a, 50..30000)) null else "wait ต้องเป็น 50–30000"
        "launch", "stop" -> if (Tweaks.PKG_RE.matches(a)) null else "ชื่อ package ไม่ถูกต้อง"
        "boost", "screenshot" -> if (a.isEmpty()) null else "$cmd ไม่ต้องมีค่าต่อท้าย"
        "toast" -> if (a.isNotEmpty() && a.length <= 80) null else "toast ต้องมีข้อความ 1–80 ตัวอักษร"
        else -> "ไม่รู้จักคำสั่ง '$cmd'"
    }

    suspend fun run(ctx: Context, m: Macro, log: (String) -> Unit = {}): Boolean {
        val parsed = parse(m.script)
        if (parsed.errors.isNotEmpty()) { parsed.errors.forEach(log); return false }
        if (!mutex.tryLock()) { log("มีมาโครอื่นกำลังทำงานอยู่"); return false }
        running.value = m.name
        try {
            if (Assist.ensureShell() == Shell.Mode.NONE) { log("ไม่มีสิทธิ์ Shizuku/Root"); return false }
            val p = Prefs(ctx)
            var allOk = true
            log("▶ เริ่ม: ${m.name}")
            for (s in parsed.steps) {
                val r = runCatching { exec(ctx, p, s) }.getOrElse { Shell.Result(-1, "", it.message ?: "error") }
                if (!r.ok) allOk = false
                log((if (r.ok) "✓ " else "✗ ") + "${s.cmd} ${s.arg}".trim() + (if (r.ok) "" else " — ${r.err.take(80)}"))
            }
            log(if (allOk) "■ เสร็จสมบูรณ์" else "■ เสร็จ (บางคำสั่งล้มเหลว)")
            return allOk
        } finally {
            running.value = null
            mutex.unlock()
        }
    }

    private suspend fun exec(ctx: Context, p: Prefs, s: Step): Shell.Result {
        val a = s.arg
        return when (s.cmd) {
            "dpi" -> if (a == "game") Tweaks.enterGameProfile(p) else {
                if (p.inGameProfile) { p.inGameProfile = false; p.preGameDpi = -1 }
                Shell.exec(if (a == "reset") "wm density reset" else "wm density ${a.toInt()}")
            }
            "size" -> Shell.exec(if (a == "reset") "wm size reset" else "wm size $a")
            "hz" -> Tweaks.lockRefreshRate(if (a == "reset") null else a.toInt(), p)
            "touch" -> Tweaks.touchBoost(a == "on", p)
            "pointer" -> Tweaks.setPointerSpeed(a.toInt())
            "dnd" -> GameMode.setDnd(a == "on")
            "headsup" -> Shell.exec("settings put global heads_up_notifications_enabled ${if (a == "on") 1 else 0}")
            "rotate" -> Shell.exec("settings put system accelerometer_rotation ${if (a == "auto") 1 else 0}")
            "boost" -> Shell.Result(0, "${Tweaks.boostRam(p.gamePackages + ctx.packageName)} apps", "")
            "launch" -> Shell.exec("monkey -p $a -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1")
                .let { if (it.ok) it else it.copy(err = "ไม่พบแอป $a") }
            "stop" -> if (a == ctx.packageName) Shell.Result(-1, "", "หยุดแอปตัวเองไม่ได้") else Shell.exec("am force-stop $a")
            "wait" -> { delay(a.toLong()); OK }
            "screenshot" -> Tools.screenshot()
            "toast" -> { withContext(Dispatchers.Main) { Toast.makeText(ctx, a, Toast.LENGTH_SHORT).show() }; OK }
            else -> Shell.Result(-1, "", "unknown")
        }
    }
}

/** ภาพหน้าจอ / อัดจอ ผ่าน shell (ไม่ต้องขอสิทธิ์เพิ่ม) */
object Tools {
    private const val SS_DIR = "/sdcard/Pictures/GameAssist"
    private const val REC_DIR = "/sdcard/Movies/GameAssist"
    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    private fun scan(f: String) = "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$f >/dev/null 2>&1"

    suspend fun screenshot(): Shell.Result {
        val f = "$SS_DIR/ss_${stamp()}.png"
        return Shell.exec("mkdir -p $SS_DIR; screencap -p $f; ${scan(f)}; [ -f $f ]")
    }

    suspend fun isRecording(): Boolean = Shell.exec("pidof screenrecord", 3).let { it.ok && it.out.isNotBlank() }

    suspend fun startRecord(seconds: Int = 180): Shell.Result {
        val f = "$REC_DIR/rec_${stamp()}.mp4"
        return Shell.exec("mkdir -p $REC_DIR; nohup sh -c 'screenrecord --time-limit $seconds $f; ${scan(f)}' >/dev/null 2>&1 &")
    }

    suspend fun stopRecord() = Shell.exec("pkill -INT screenrecord")
}
