package com.dpi.gamingtweaker

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val HCardBg = Color(0xFF161622)
private val HTile = Color(0xFF1E1E2C)
private val HYellow = Color(0xFFFFD600)
private val HCyan = Color(0xFF00E5FF)
private val HGreen = Color(0xFF00E676)
private val HRed = Color(0xFFFF1744)

@Composable
private fun HCard(title: String, accent: Color, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = HCardBg),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = .35f))
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = accent, fontWeight = FontWeight.Black, fontSize = 16.sp, letterSpacing = 1.sp)
            content()
        }
    }
}

@Composable
private fun HToggle(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Color.Gray, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun HIntSlider(label: String, value: Int, range: IntRange, step: Int, suffix: String, onChange: (Int) -> Unit) {
    Column {
        val shown = if (value == 0 && range.first == 0) "ปิด" else "$value$suffix"
        Text("$label: $shown", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        val n = ((range.last - range.first) / step - 1).coerceAtLeast(0)
        Slider(
            value = value.toFloat().coerceIn(range.first.toFloat(), range.last.toFloat()),
            onValueChange = { onChange(((it / step).roundToInt() * step).coerceIn(range)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = n,
        )
    }
}

// ============================ GAME ASSIST CARD ============================
@Composable
fun GameAssistCard(hasShell: Boolean) {
    val ctx = LocalContext.current
    val ap = remember { AssistPrefs(ctx) }
    var enabled by remember { mutableStateOf(ap.enabled) }
    var dnd by remember { mutableStateOf(ap.dnd) }
    var noHeadsUp by remember { mutableStateOf(ap.noHeadsUp) }
    var lockBright by remember { mutableStateOf(ap.lockBrightness) }
    var toolbox by remember { mutableStateOf(ap.toolbox) }
    var vibrate by remember { mutableStateOf(ap.vibrate) }
    var heat by remember { mutableIntStateOf(ap.heatAlertC) }
    var brk by remember { mutableIntStateOf(ap.breakMin) }
    var bat by remember { mutableIntStateOf(ap.batteryAlert) }
    val serviceLive by GameDetectorService.live.collectAsState()
    val inGame by GameDetectorService.gameRunning.collectAsState()

    HCard("GAME ASSIST — ผู้ช่วยเล่นเกม", HYellow) {
        Text(
            when {
                !hasShell -> "ต้องเชื่อมต่อ Shizuku/Root ก่อน"
                !serviceLive -> "Auto-Apply Service ยังไม่ทำงาน — เปิดได้ที่ศูนย์ช่วยเหลือด้านล่าง"
                inGame -> "อยู่ในเกม — ผู้ช่วยกำลังทำงาน"
                else -> "พร้อมแล้ว — จะเริ่มทำงานเองเมื่อเปิดเกม"
            },
            color = if (hasShell && serviceLive) HGreen else HRed, fontSize = 12.sp
        )
        HToggle("เปิดผู้ช่วยอัตโนมัติเมื่อเข้าเกม", "ใช้รายชื่อเกมเดียวกับการ์ด AUTO-APPLY", enabled) {
            enabled = it; ap.enabled = it
        }
        HToggle("ห้ามรบกวน (DND)", "ระหว่างเล่นจะไม่มีเสียงหรือแจ้งเตือนกวน", dnd) { dnd = it; ap.dnd = it }
        HToggle("ปิดแจ้งเตือนเด้งบังจอ", "แจ้งเตือนจะไม่เด้งทับปุ่มยิง", noHeadsUp) { noHeadsUp = it; ap.noHeadsUp = it }
        HToggle("ล็อกความสว่าง", "ปิดความสว่างอัตโนมัติระหว่างเล่น ออกจากเกมแล้วคืนค่าเดิม", lockBright) {
            lockBright = it; ap.lockBrightness = it
        }
        HToggle("Floating Toolbox", "ปุ่มลอยในเกม: เวลาเล่น / อุณหภูมิ / แบต + แผงลัด", toolbox) {
            toolbox = it; ap.toolbox = it
        }
        HToggle("สั่นเตือน", "สั่นเบาๆ ตอนเครื่องร้อน แบตต่ำ หรือถึงเวลาพัก", vibrate) { vibrate = it; ap.vibrate = it }
        HIntSlider("เตือนเครื่องร้อน (วัดจากแบต)", heat, 35..50, 1, "°C") { heat = it; ap.heatAlertC = it }
        HIntSlider("เตือนพักทุก", brk, 0..180, 15, " นาที") { brk = it; ap.breakMin = it }
        HIntSlider("เตือนแบตต่ำกว่า", bat, 5..30, 5, "%") { bat = it; ap.batteryAlert = it }
        Text("การตั้งค่าจะมีผลในเกมรอบถัดไป", color = Color.Gray, fontSize = 11.sp)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val s = GameDetectorService.instance
                    if (s == null) Toast.makeText(ctx, "ต้องเปิด Auto-Apply Service ก่อน", Toast.LENGTH_LONG).show()
                    else s.startSession(forceToolbox = true)
                },
                modifier = Modifier.weight(1f)
            ) { Text("ทดสอบ Toolbox") }
            OutlinedButton(
                onClick = { GameDetectorService.instance?.stopSession() },
                modifier = Modifier.weight(1f)
            ) { Text("ปิด Toolbox") }
        }
    }
}

// ============================ HELP CENTER ============================
private val FAQ = listOf(
    "จอดำหรือค้างหลังเปลี่ยน DPI" to
        "ไม่ต้องทำอะไร รอประมาณ 13 วินาที ระบบ Fail-Safe จะคืนค่าให้เอง ถ้ายังไม่หาย ให้ดึงแผงตั้งค่าด่วนลงมาแล้วกดปุ่ม DPI Reset ถ้าแตะจอไม่ได้เลย ให้ต่อคอมแล้วสั่ง adb shell wm density reset",
    "รีสตาร์ตเครื่องแล้วแอปใช้ไม่ได้" to
        "ถ้าใช้ Shizuku แบบไม่ Root ต้องเปิดแอป Shizuku แล้วกด Start ใหม่ทุกครั้งหลังรีสตาร์ต ค่า DPI ที่ตั้งไว้จะยังอยู่",
    "Free Fire เด้งหรือโหลดใหม่ตอนเปลี่ยน DPI" to
        "เกม Unity จะโหลดใหม่เมื่อ DPI เปลี่ยนระหว่างเล่น ให้ใช้ปุ่ม 'เปิดเกม @ DPI' ในการ์ด AUTO-APPLY แทน แอปจะตั้ง DPI ก่อนแล้วค่อยเปิดเกม",
    "ผู้ช่วยเล่นเกมไม่ขึ้นตอนเข้าเกม" to
        "ตรวจ 3 อย่าง: (1) เชื่อมต่อ Shizuku/Root แล้ว (2) Auto-Apply Service เปิดอยู่ (3) สวิตช์ 'เปิดผู้ช่วยอัตโนมัติ' เปิดอยู่ และ package เกมอยู่ในรายชื่อ",
    "DND หรือแจ้งเตือนค้างหลังออกเกม" to
        "กดปุ่ม EMERGENCY RESET สีแดง หรือปุ่ม DPI Reset ในแผงตั้งค่าด่วน แอปจะคืนค่า DND, แจ้งเตือนเด้ง และความสว่างให้เหมือนก่อนเข้าเกม",
    "Android 13 ขึ้นไปเปิด Accessibility ไม่ได้" to
        "ไปที่ ตั้งค่า > แอป > DPI Gaming Tweaker แล้วกด ⋮ เลือก 'อนุญาตการตั้งค่าที่ถูกจำกัด' หรือกดสวิตช์ Auto-Apply ในแอปขณะเชื่อมต่อ Shizuku อยู่ ระบบจะเปิดให้เอง",
    "FPS ใน Overlay ขึ้นเป็น Hz" to
        "รอมของเครื่องบล็อกตัวนับเฟรมของ SurfaceFlinger แอปเลยแสดงค่า refresh rate ของจอแทน กด Self-Test เพื่อตรวจดูได้",
    "ควรตั้ง DPI เท่าไหร่" to
        "แต่ละเครื่องไม่เท่ากัน ให้เริ่มจากค่าโรงงาน ปรับครั้งละ 20–50 แล้วลองในโหมดฝึกก่อนเล่นจริง",
    "Xiaomi / POCO สั่งคำสั่งไม่ได้" to
        "ไปที่ตัวเลือกสำหรับนักพัฒนา แล้วเปิด 'USB debugging (Security settings)' จากนั้นเริ่ม Shizuku ใหม่",
    "ใช้แอปนี้แล้วโดนแบนไหม" to
        "แอปนี้แก้เฉพาะค่าตั้งค่าของระบบ Android ไม่ได้แตะไฟล์หรือหน่วยความจำของเกม และไม่มีระบบช่วยเล็ง แต่ก็ควรใช้อย่างระมัดระวังและรับความเสี่ยงเอง",
)

@Composable
private fun CheckRow(title: String, ok: Boolean, onFix: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (ok) "✓" else "✗",
            color = if (ok) HGreen else HRed,
            fontWeight = FontWeight.Black, fontSize = 20.sp,
            modifier = Modifier.width(28.dp)
        )
        Text(title, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (!ok && onFix != null) TextButton(onClick = onFix) { Text("แก้ไข") }
    }
}

@Composable
fun HelpCenterCard(mode: Shell.Mode, onRequestShizuku: () -> Unit, onRecheck: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }

    val overlayOk = remember(tick) { Settings.canDrawOverlays(ctx) }
    val a11yOk = remember(tick) { isA11yEnabled(ctx) }
    val notifOk = remember(tick) {
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }
    var report by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var openFaq by remember { mutableIntStateOf(-1) }

    fun open(intent: Intent) {
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(ctx, "เปิดหน้าตั้งค่าไม่ได้", Toast.LENGTH_SHORT).show() }
    }

    HCard("HELP CENTER — ศูนย์ช่วยเหลือ", HCyan) {
        Text("เช็กลิสต์การตั้งค่า", color = Color.White, fontWeight = FontWeight.Bold)
        CheckRow("Shizuku / Root", mode != Shell.Mode.NONE) { onRequestShizuku(); onRecheck() }
        CheckRow("สิทธิ์แสดงทับแอป (Overlay)", overlayOk) {
            open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
        }
        CheckRow("Auto-Apply Service (Accessibility)", a11yOk) {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        CheckRow("สิทธิ์แจ้งเตือน", notifOk) {
            open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        }
        OutlinedButton(
            onClick = {
                if (Build.VERSION.SDK_INT >= 33) {
                    runCatching {
                        ctx.getSystemService(StatusBarManager::class.java)?.requestAddTileService(
                            ComponentName(ctx, EmergencyTile::class.java),
                            "DPI Reset",
                            Icon.createWithResource(ctx, android.R.drawable.ic_menu_revert),
                            ctx.mainExecutor
                        ) { }
                    }.onFailure {
                        Toast.makeText(ctx, "ดึงแผงตั้งค่าด่วนลงมา > กดรูปดินสอ > ลาก 'DPI Reset' เข้าไป", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(
                        ctx, "ดึงแผงตั้งค่าด่วนลงมา > กดรูปดินสอ > ลาก 'DPI Reset' เข้าไป", Toast.LENGTH_LONG
                    ).show()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("เพิ่มปุ่ม DPI Reset ลงแผงตั้งค่าด่วน") }

        Text("คำถามที่พบบ่อย", color = Color.White, fontWeight = FontWeight.Bold)
        FAQ.forEachIndexed { i, (q, a) ->
            Column(
                Modifier.fillMaxWidth()
                    .background(HTile, RoundedCornerShape(12.dp))
                    .clickable { openFaq = if (openFaq == i) -1 else i }
                    .padding(12.dp)
            ) {
                Text((if (openFaq == i) "▾ " else "▸ ") + q, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                if (openFaq == i) {
                    Spacer(Modifier.height(6.dp))
                    Text(a, color = Color.LightGray, fontSize = 13.sp)
                }
            }
        }

        Text("ตรวจระบบอัตโนมัติ", color = Color.White, fontWeight = FontWeight.Bold)
        Button(
            onClick = {
                testing = true
                scope.launch {
                    report = runCatching { Assist.selfTest(ctx) }.getOrElse { "Self-test error: ${it.message}" }
                    testing = false
                    onRecheck()
                }
            },
            enabled = !testing,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (testing) "กำลังตรวจ..." else "เริ่ม Self-Test") }

        if (report.isNotBlank()) {
            Text(
                report,
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color.LightGray,
                modifier = Modifier.fillMaxWidth().background(HTile, RoundedCornerShape(12.dp)).padding(10.dp)
            )
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(report))
                    Toast.makeText(ctx, "คัดลอกแล้ว วางส่งให้ผู้ช่วยได้เลย", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("คัดลอกรายงาน") }
        }
    }
}
