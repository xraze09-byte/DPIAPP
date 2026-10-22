package com.dpi.gamingtweaker

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

// ============================ BACKUP / RESTORE ============================
object Backup {
    private val FILES = listOf("dpi_tweaker", "game_assist", "dpi_ui", "macros")
    // ค่าสถานะชั่วคราว ไม่สำรอง (ถ้านำเข้าไปเครื่องอื่นจะคืนค่าผิด)
    private val VOLATILE = setOf("restore_cmd", "session_active", "in_game", "pre_game_dpi", "refresh_restore", "touch_restore")

    fun export(ctx: Context): String {
        val root = JSONObject().put("app", "dpi_gaming_tweaker").put("v", 1)
        for (name in FILES) {
            val o = JSONObject()
            for ((k, v) in ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                if (k in VOLATILE || v == null) continue
                val e = when (v) {
                    is Boolean -> JSONObject().put("t", "b").put("v", v)
                    is Int -> JSONObject().put("t", "i").put("v", v)
                    is Long -> JSONObject().put("t", "l").put("v", v)
                    is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                    is String -> JSONObject().put("t", "s").put("v", v)
                    is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                    else -> null
                } ?: continue
                o.put(k, e)
            }
            root.put(name, o)
        }
        return root.toString()
    }

    fun restore(ctx: Context, text: String): String {
        val root = runCatching { JSONObject(text.trim()) }.getOrNull()
            ?: return "ข้อมูลไม่ถูกต้อง (ต้องเป็นข้อความที่คัดลอกจากปุ่มสำรอง)"
        if (root.optString("app") != "dpi_gaming_tweaker") return "ไม่ใช่ข้อมูลสำรองของแอปนี้"
        var n = 0
        for (name in FILES) {
            val o = root.optJSONObject(name) ?: continue
            ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit(commit = true) {
                for (k in o.keys()) {
                    if (k in VOLATILE) continue
                    val e = o.optJSONObject(k) ?: continue
                    when (e.optString("t")) {
                        "b" -> putBoolean(k, e.optBoolean("v"))
                        "i" -> putInt(k, e.optInt("v"))
                        "l" -> putLong(k, e.optLong("v"))
                        "f" -> putFloat(k, e.optDouble("v").toFloat())
                        "s" -> putString(k, e.optString("v"))
                        "set" -> {
                            val a = e.optJSONArray("v") ?: continue
                            putStringSet(k, (0 until a.length()).map { a.optString(it) }.toSet())
                        }
                        else -> continue
                    }
                    n++
                }
            }
        }
        return if (n > 0) "นำเข้า $n ค่าแล้ว — ปัดแอปทิ้งจากหน้าแอปล่าสุดแล้วเปิดใหม่" else "ไม่พบค่าที่นำเข้าได้"
    }
}

// ============================ NETWORK LAB ============================
object NetLab {
    data class Server(val name: String, val ip: String, val dot: String)
    data class Probe(val server: Server, val avg: Int?, val jitter: Int?, val loss: Int) {
        val score: Int get() = if (avg == null) Int.MAX_VALUE else avg + 2 * (jitter ?: 0) + loss * 10
    }

    val SERVERS = listOf(
        Server("Cloudflare", "1.1.1.1", "one.one.one.one"),
        Server("Google", "8.8.8.8", "dns.google"),
        Server("Quad9", "9.9.9.9", "dns.quad9.net"),
    )
    private val HOST_RE = Regex("""[a-zA-Z0-9.-]{1,100}""")
    private val MODES = setOf("off", "opportunistic", "hostname")

    private fun sp(ctx: Context) = ctx.getSharedPreferences("lab", Context.MODE_PRIVATE)
    fun changed(ctx: Context) = sp(ctx).contains("dns_mode")

    suspend fun probe(s: Server, n: Int = 10): Probe = withContext(Dispatchers.IO) {
        val rtts = mutableListOf<Int>()
        repeat(n) {
            Metrics.tcpPing(s.ip, 53, 1000)?.let { rtts += it }
            delay(120)
        }
        val loss = (n - rtts.size) * 100 / n
        if (rtts.isEmpty()) return@withContext Probe(s, null, null, loss)
        val jitter = if (rtts.size > 1) rtts.zipWithNext { a, b -> abs(a - b) }.average().roundToInt() else 0
        Probe(s, rtts.average().roundToInt(), jitter, loss)
    }

    private suspend fun resolves(): Boolean = withContext(Dispatchers.IO) {
        repeat(3) {
            if (runCatching { InetAddress.getAllByName("www.google.com").isNotEmpty() }.getOrDefault(false)) return@withContext true
            delay(1500)
        }
        false
    }

    /** ตั้ง Private DNS แล้วทดสอบทันที ถ้าใช้ไม่ได้ จะคืนค่าเดิมเอง (Fail-Safe) */
    suspend fun applyDns(ctx: Context, s: Server): String {
        val p = sp(ctx)
        if (!p.contains("dns_mode")) {
            val mode = Shell.exec("settings get global private_dns_mode").out.trim()
            val spec = Shell.exec("settings get global private_dns_specifier").out.trim()
            p.edit(commit = true) { putString("dns_mode", mode); putString("dns_spec", spec) }
        }
        val r = Shell.exec("settings put global private_dns_specifier ${s.dot}; settings put global private_dns_mode hostname")
        if (!r.ok) return "ตั้ง DNS ไม่สำเร็จ: ${r.err.take(80)}"
        delay(3000)
        if (resolves()) return "ใช้ Private DNS (${s.name}: ${s.dot}) แล้ว"
        restoreDns(ctx)
        return "${s.name} ใช้ไม่ได้บนเครือข่ายนี้ คืนค่า DNS เดิมให้แล้ว"
    }

    suspend fun restoreDns(ctx: Context): String {
        val p = sp(ctx)
        if (!p.contains("dns_mode")) return "DNS ยังเป็นค่าเดิมอยู่"
        val mode = p.getString("dns_mode", null)
        val spec = p.getString("dns_spec", null)
        val cmd = buildString {
            if (spec != null && spec != "null" && HOST_RE.matches(spec)) append("settings put global private_dns_specifier $spec; ")
            else append("settings delete global private_dns_specifier; ")
            if (mode != null && mode in MODES) append("settings put global private_dns_mode $mode")
            else append("settings delete global private_dns_mode")
        }
        val r = Shell.exec(cmd)
        if (r.ok) p.edit(commit = true) { remove("dns_mode"); remove("dns_spec") }
        return if (r.ok) "คืนค่า DNS เดิมแล้ว" else "คืนค่าไม่สำเร็จ: ${r.err.take(80)}"
    }
}

// ============================ LIVE HISTORY ============================
private object LiveHistory {
    val ping = mutableStateListOf<Float>()
    val temp = mutableStateListOf<Float>()
    val ram = mutableStateListOf<Float>()
    var last: Metrics.Telemetry? = null
    fun push(list: MutableList<Float>, v: Float) {
        list.add(v)
        if (list.size > 90) list.removeAt(0)
    }
}

// ============================ LAB SCREEN ============================
@Composable
fun LabScreen(vm: MainViewModel) {
    val t = vm.telemetry
    LaunchedEffect(t) {
        if (t.ramTotalMb == 0L || LiveHistory.last === t) return@LaunchedEffect
        LiveHistory.last = t
        t.pingMs?.let { LiveHistory.push(LiveHistory.ping, it.toFloat()) }
        t.cpuTempC?.let { LiveHistory.push(LiveHistory.temp, it) }
        LiveHistory.push(LiveHistory.ram, 100f * (t.ramTotalMb - t.ramAvailMb) / t.ramTotalMb)
    }
    HealthCard(vm)
    GlassCard("กราฟสด", NeonOrange, "ย้อนหลังประมาณ 3 นาที (บันทึกเฉพาะตอนเปิดแท็บนี้)") {
        Sparkline("Ping", LiveHistory.ping, " ms", NeonCyan)
        Sparkline("อุณหภูมิ (${t.tempSource})", LiveHistory.temp, "°C", NeonOrange)
        Sparkline("RAM ที่ใช้", LiveHistory.ram, "%", NeonPurple)
    }
    NetworkLabCard(vm)
    TouchLabCard()
    ReactionCard()
    BackupCard()
}

@Composable
private fun LabStat(title: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(TileDark).padding(10.dp)) {
        Text(title, color = Color.Gray, fontSize = 11.sp)
        Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
    }
}

// ---------- คะแนนความพร้อม ----------
@Composable
private fun HealthCard(vm: MainViewModel) {
    val ctx = LocalContext.current
    val t = vm.telemetry
    val storagePct = remember(t) {
        runCatching {
            val s = StatFs(Environment.getDataDirectory().path)
            (s.availableBytes * 100 / s.totalBytes).toInt()
        }.getOrDefault(50)
    }
    val bat = remember(t) { Assist.battery(ctx) }
    val tips = mutableListOf<String>()
    var score = 100
    val ramFree = if (t.ramTotalMb > 0) (t.ramAvailMb * 100 / t.ramTotalMb).toInt() else 50

    when {
        ramFree < 20 -> { score -= 20; tips += "RAM ว่างเหลือ $ramFree% — กด RAM Boost ก่อนเข้าเกม" }
        ramFree < 35 -> { score -= 8; tips += "RAM ว่าง $ramFree% — ปิดแอปที่ไม่ใช้ก่อนเล่น" }
    }
    val temp = t.cpuTempC
    when {
        temp == null -> {}
        temp >= 45f -> { score -= 25; tips += "เครื่องร้อน ${temp.roundToInt()}°C — ถอดเคส พักเครื่อง หรือลดกราฟิกในเกม" }
        temp >= 40f -> { score -= 10; tips += "เครื่องเริ่มอุ่น ${temp.roundToInt()}°C — ถ้าร้อนกว่านี้เครื่องจะลดความเร็วลง" }
    }
    val ping = t.pingMs
    when {
        ping == null -> { score -= 25; tips += "วัด Ping ไม่ได้ — ตรวจการเชื่อมต่อเน็ต" }
        ping >= 120 -> { score -= 20; tips += "Ping $ping ms สูง — ขยับใกล้เราเตอร์หรือใช้ Wi-Fi 5GHz" }
        ping >= 60 -> { score -= 8; tips += "Ping $ping ms พอใช้ — ลองทดสอบใน Network Lab ด้านล่าง" }
    }
    if (storagePct < 10) { score -= 10; tips += "พื้นที่ว่างเหลือ $storagePct% — พื้นที่เต็มทำให้เกมโหลดช้า" }
    if (bat.pct in 0..20 && !bat.charging) { score -= 10; tips += "แบตเหลือ ${bat.pct}% — บางเครื่องลดประสิทธิภาพเมื่อแบตต่ำ" }
    if (t.refreshHz in 1f..89f) tips += "จอทำงานที่ ${t.refreshHz.roundToInt()}Hz — ถ้าจอรองรับ 90Hz เปิด Lock 90Hz ได้ที่แท็บผู้ช่วย"

    val s = score.coerceIn(0, 100)
    val c = when { s >= 80 -> NeonGreen; s >= 55 -> NeonYellow; else -> NeonRed }
    GlassCard("ความพร้อมก่อนเล่น", c, "คะแนนจาก RAM, อุณหภูมิ, Ping, พื้นที่ และแบต") {
        if (t.ramTotalMb == 0L) {
            Text("กำลังวัด...", color = Color.Gray)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$s", fontSize = 56.sp, fontWeight = FontWeight.Black, color = c, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        when { s >= 80 -> "พร้อมเล่น"; s >= 55 -> "พอใช้"; else -> "ควรแก้ไขก่อนเล่น" },
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp
                    )
                    Text("RAM ว่าง $ramFree% • พื้นที่ว่าง $storagePct%", color = Color.Gray, fontSize = 12.sp)
                }
            }
            if (tips.isEmpty()) Text("ทุกอย่างพร้อม ลุยได้เลย", color = NeonGreen, fontSize = 13.sp)
            tips.forEach { Text("• $it", color = Color.LightGray, fontSize = 13.sp) }
            GradientButton(
                if (vm.busy) "กำลังทำงาน..." else "RAM BOOST",
                listOf(Color(0xFF00C853), Color(0xFF00E676)), vm.hasShell && !vm.busy, 48.dp
            ) { vm.boostRam() }
        }
    }
}

// ---------- กราฟเส้น ----------
@Composable
private fun Sparkline(title: String, values: List<Float>, unit: String, color: Color) {
    val last = values.lastOrNull()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(TileDark).padding(12.dp)) {
        Row {
            Text(title, color = Color.Gray, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(
                last?.let { "%.0f".format(it) + unit } ?: "--",
                color = color, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
            )
        }
        Canvas(Modifier.fillMaxWidth().height(56.dp).padding(top = 6.dp)) {
            if (values.size < 2) return@Canvas
            var lo = values.min()
            var hi = values.max()
            if (hi - lo < 1f) { hi += 0.5f; lo -= 0.5f }
            val stepX = size.width / (values.size - 1)
            val path = Path()
            values.forEachIndexed { i, v ->
                val x = i * stepX
                val y = size.height - (v - lo) / (hi - lo) * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
        }
        if (values.isNotEmpty()) {
            Text(
                "เฉลี่ย " + "%.0f".format(values.average()) + unit + " • สูงสุด " + "%.0f".format(values.max()) + unit,
                color = Color.Gray, fontSize = 11.sp
            )
        }
    }
}

// ---------- Network Lab ----------
@Composable
private fun NetworkLabCard(vm: MainViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf<List<NetLab.Probe>>(emptyList()) }
    var testing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    var dnsChanged by remember { mutableStateOf(NetLab.changed(ctx)) }

    GlassCard("NETWORK LAB", NeonCyan, "วัด Ping, Jitter, Packet loss แล้วเลือก DNS ที่ดีที่สุด") {
        GradientButton(
            if (testing) "กำลังทดสอบ $progress" else "เริ่มทดสอบเครือข่าย",
            listOf(Color(0xFF0091EA), Color(0xFF00B8D4)), !testing
        ) {
            testing = true
            msg = ""
            scope.launch {
                val out = mutableListOf<NetLab.Probe>()
                NetLab.SERVERS.forEachIndexed { i, s ->
                    progress = "${i + 1}/${NetLab.SERVERS.size}"
                    out += NetLab.probe(s)
                }
                results = out.sortedBy { it.score }
                testing = false
            }
        }
        results.forEachIndexed { i, r ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TileDark).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        r.server.name + if (i == 0 && r.avg != null) "  • แนะนำ" else "",
                        color = if (i == 0 && r.avg != null) NeonGreen else Color.White, fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (r.avg == null) "เชื่อมต่อไม่ได้"
                        else "Ping ${r.avg} ms • Jitter ${r.jitter} ms • Loss ${r.loss}%",
                        color = pingColor(r.avg), fontSize = 12.sp, fontFamily = FontFamily.Monospace
                    )
                }
                TextButton(
                    enabled = vm.hasShell && r.avg != null,
                    onClick = {
                        scope.launch {
                            msg = "กำลังตั้งค่าและทดสอบ..."
                            msg = NetLab.applyDns(ctx, r.server)
                            dnsChanged = NetLab.changed(ctx)
                        }
                    }
                ) { Text("ใช้ DNS นี้") }
            }
        }
        if (dnsChanged) {
            OutlinedButton(
                onClick = { scope.launch { msg = NetLab.restoreDns(ctx); dnsChanged = NetLab.changed(ctx) } },
                enabled = vm.hasShell, modifier = Modifier.fillMaxWidth()
            ) { Text("คืนค่า DNS เดิม") }
        }
        if (msg.isNotBlank()) Text(msg, color = NeonYellow, fontSize = 13.sp)
        Text(
            "DNS ช่วยให้เชื่อมต่อครั้งแรกเร็วและเสถียรขึ้น แต่ไม่ได้ลด Ping ระหว่างแมตช์ เพราะค่านั้นขึ้นกับระยะทางถึงเซิร์ฟเวอร์เกม ส่วน Jitter และ Loss ที่ต่ำจะช่วยให้เกมลื่นกว่า",
            color = Color.Gray, fontSize = 11.sp
        )
    }
}

// ---------- Touch Lab ----------
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TouchLabCard() {
    var hz by remember { mutableIntStateOf(0) }
    var peakHz by remember { mutableIntStateOf(0) }
    var fingers by remember { mutableIntStateOf(0) }
    var maxFingers by remember { mutableIntStateOf(0) }
    var speed by remember { mutableIntStateOf(0) }
    val trail = remember { mutableStateListOf<Offset>() }

    GlassCard("TOUCH LAB", NeonOrange, "วัดความถี่จอสัมผัส ความเร็วการลาก และจำนวนนิ้ว") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabStat("Touch rate", if (hz > 0) "$hz Hz" else "--", Modifier.weight(1f))
            LabStat("สูงสุด", if (peakHz > 0) "$peakHz Hz" else "--", Modifier.weight(1f))
            LabStat("นิ้ว", "$fingers / $maxFingers", Modifier.weight(1f))
        }
        Box(
            Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(16.dp)).background(TileDark)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        var samples = 0
                        var windowStart = 0L
                        while (true) {
                            val ev = awaitPointerEvent()
                            val down = ev.changes.count { it.pressed }
                            fingers = down
                            if (down > maxFingers) maxFingers = down
                            val c = ev.changes.firstOrNull { it.pressed }
                            if (c == null) { windowStart = 0L; continue }
                            if (windowStart == 0L) { windowStart = c.uptimeMillis; samples = 0; trail.clear() }
                            samples += c.historical.size + 1
                            c.historical.forEach { trail.add(it.position) }
                            trail.add(c.position)
                            while (trail.size > 400) trail.removeAt(0)
                            val dt = (c.uptimeMillis - c.previousUptimeMillis).coerceAtLeast(1L)
                            speed = ((c.position - c.previousPosition).getDistance() / dt * 1000f / density).roundToInt()
                            val elapsed = c.uptimeMillis - windowStart
                            if (elapsed >= 500) {
                                hz = (samples * 1000L / elapsed).toInt()
                                if (hz > peakHz) peakHz = hz
                                windowStart = c.uptimeMillis
                                samples = 0
                            }
                            ev.changes.forEach { it.consume() }
                        }
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                for (i in 1 until trail.size) drawLine(NeonCyan, trail[i - 1], trail[i], strokeWidth = 4f)
            }
            if (trail.isEmpty()) {
                Text("ลากนิ้วไปมาในกรอบนี้", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
            }
        }
        Text("ความเร็วการลาก: $speed dp/วินาที", color = Color.White, fontSize = 13.sp)
        Text(
            "Touch rate คือความถี่ที่จอสัมผัสส่งข้อมูล ค่านี้มาจากฮาร์ดแวร์ การปรับ DPI ไม่ได้ทำให้เปลี่ยน แต่ใช้ดูว่าจอยังตอบสนองปกติไหมเวลาเครื่องร้อน",
            color = Color.Gray, fontSize = 11.sp
        )
        OutlinedButton(onClick = { hz = 0; peakHz = 0; maxFingers = 0; speed = 0; trail.clear() }) { Text("ล้างผล") }
    }
}

// ---------- Reaction Test ----------
@Composable
private fun ReactionCard() {
    var phase by remember { mutableIntStateOf(0) } // 0 พร้อม, 1 รอ, 2 แตะ!, 3 ผล, 4 เร็วไป
    var goAt by remember { mutableLongStateOf(0L) }
    var last by remember { mutableLongStateOf(0L) }
    val results = remember { mutableStateListOf<Long>() }
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }

    fun press() {
        when (phase) {
            1 -> { job?.cancel(); phase = 4 }
            2 -> {
                last = SystemClock.uptimeMillis() - goAt
                results.add(last)
                if (results.size > 10) results.removeAt(0)
                phase = 3
            }
            else -> {
                phase = 1
                job = scope.launch {
                    delay(Random.nextLong(1500, 4000))
                    goAt = SystemClock.uptimeMillis()
                    phase = 2
                }
            }
        }
    }

    val (bg, msg) = when (phase) {
        1 -> NeonRed to "รอสีเขียว..."
        2 -> NeonGreen to "แตะเลย!"
        3 -> TileDark to "$last ms\nแตะเพื่อเล่นอีกครั้ง"
        4 -> NeonYellow to "เร็วเกินไป!\nแตะเพื่อเริ่มใหม่"
        else -> TileDark to "แตะเพื่อเริ่ม"
    }
    GlassCard("REACTION TEST", NeonGreen, "วัดความไวในการตอบสนอง (รวมดีเลย์ของจอแล้ว)") {
        Box(
            Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp)).background(bg)
                .pointerInput(Unit) { detectTapGestures(onPress = { press() }) },
            contentAlignment = Alignment.Center
        ) {
            Text(
                msg, color = if (phase == 2 || phase == 4) Color.Black else Color.White,
                fontWeight = FontWeight.Black, fontSize = 20.sp, textAlign = TextAlign.Center
            )
        }
        if (results.isNotEmpty()) {
            Text(
                "เฉลี่ย ${results.average().roundToInt()} ms • ดีที่สุด ${results.min()} ms • ${results.size} ครั้ง",
                color = Color.LightGray, fontSize = 13.sp
            )
        }
    }
}

// ---------- Backup ----------
@Composable
private fun BackupCard() {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var msg by remember { mutableStateOf("") }
    GlassCard("สำรอง / กู้คืนการตั้งค่า", NeonPurple, "ย้ายโปรไฟล์ พรีเซ็ต และมาโครไปเครื่องใหม่ได้") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    clipboard.setText(AnnotatedString(Backup.export(ctx)))
                    msg = "คัดลอกข้อมูลสำรองแล้ว — วางเก็บไว้ในโน้ตหรือแชทของตัวเอง"
                },
                modifier = Modifier.weight(1f)
            ) { Text("สำรอง (คัดลอก)") }
            OutlinedButton(
                onClick = {
                    val text = clipboard.getText()?.text
                    msg = if (text.isNullOrBlank()) "คลิปบอร์ดว่าง — คัดลอกข้อมูลสำรองก่อน" else Backup.restore(ctx, text)
                },
                modifier = Modifier.weight(1f)
            ) { Text("กู้คืน (วาง)") }
        }
        if (msg.isNotBlank()) Text(msg, color = NeonYellow, fontSize = 13.sp)
    }
}
