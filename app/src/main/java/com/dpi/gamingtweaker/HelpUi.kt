package com.dpi.gamingtweaker

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
private fun PrefToggle(title: String, sub: String, initial: Boolean, enabled: Boolean = true, onSet: (Boolean) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    ToggleRow(title, sub, v, enabled) { v = it; onSet(it) }
}

@Composable
private fun PrefSlider(label: String, initial: Int, range: IntRange, step: Int, suffix: String, offAtZero: Boolean = false, onSet: (Int) -> Unit) {
    var v by remember { mutableIntStateOf(initial) }
    IntSlider(label, v, range, step, suffix, offAtZero) { v = it; onSet(it) }
}

// ============================ ASSIST SCREEN ============================
@Composable
fun AssistScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val ap = remember { AssistPrefs(ctx) }
    val live by GameDetectorService.live.collectAsState()
    val game by GameDetectorService.gameRunning.collectAsState()
    val runningMacro by MacroEngine.running.collectAsState()
    val g = game

    var autoOn by remember { mutableStateOf(ap.enabled) }
    val (stColor, stText) = when {
        !vm.hasShell -> NeonRed to "ต้องเชื่อมต่อ Shizuku/Root ก่อน"
        !live -> NeonRed to "บริการ Accessibility ยังไม่ทำงาน เมนูจึงไม่ขึ้น: กด \"เปิดบริการ\" ด้านล่าง"
        !autoOn -> NeonYellow to "ปิด \"ผู้ช่วยอัตโนมัติ\" อยู่ เมนูจะไม่ขึ้นเองตอนเข้าเกม (ใช้ปุ่มทดสอบได้)"
        g != null -> NeonGreen to "กำลังเล่น ${gameName(g)} — ผู้ช่วยทำงานอยู่"
        vm.allApps -> NeonCyan to "พร้อมแล้ว — เมนูจะขึ้นกับทุกแอปที่เปิด"
        else -> NeonCyan to "พร้อมแล้ว — เมนูขึ้นเฉพาะเกมในรายชื่อ (${vm.gamePackages.size} เกม) ถ้าเกมคุณไม่อยู่ ให้กดเพิ่มด้านล่าง"
    }
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF263238), Color(0xFF1A1030))))
            .padding(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("GAME ASSIST", color = NeonYellow, fontWeight = FontWeight.Black, fontSize = 22.sp, letterSpacing = 2.sp)
            Text(stText, color = stColor, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            runningMacro?.let { Text("มาโครกำลังทำงาน: $it", color = NeonPurple, fontSize = 12.sp) }
            PrefToggle("เปิดผู้ช่วยอัตโนมัติเมื่อเข้าเกม", "ทำงานกับทุกเกมในรายชื่อด้านล่าง", ap.enabled) { ap.enabled = it; autoOn = it }
            if (!live) {
                OutlinedButton(onClick = {
                    if (vm.hasShell) vm.enableA11y() else ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }, modifier = Modifier.fillMaxWidth()) { Text("เปิดบริการ Accessibility") }
            }
        }
    }

    GameProfileCard(vm)

    GlassCard("โหมดเล่นเกม", NeonYellow, "ใช้ตอนเข้าเกม และคืนค่าเดิมเมื่อออกเกม") {
        PrefToggle("ห้ามรบกวน (DND)", "ไม่มีเสียงหรือแจ้งเตือนกวนระหว่างเล่น", ap.dnd) { ap.dnd = it }
        PrefToggle("ปิดแจ้งเตือนเด้งบังจอ", "แจ้งเตือนจะไม่เด้งทับปุ่มยิง", ap.noHeadsUp) { ap.noHeadsUp = it }
        PrefToggle("ปิดความสว่างอัตโนมัติ", "กันจอหรี่หรือสว่างวูบระหว่างเล่น", ap.lockBrightness) { ap.lockBrightness = it }
        PrefToggle("ล็อกการหมุนจอ", "กันจอหมุนกลับด้านในเกมที่รองรับ", ap.rotationLock) { ap.rotationLock = it }
        PrefToggle(
            "Android Game Mode: Performance",
            if (Build.VERSION.SDK_INT >= 31) "ขอให้ระบบจัดทรัพยากรให้เกมเต็มที่ (ผลขึ้นกับรุ่นเครื่อง)" else "ต้องใช้ Android 12 ขึ้นไป",
            ap.androidGameMode, Build.VERSION.SDK_INT >= 31
        ) { ap.androidGameMode = it }
        PrefToggle("เคลียร์แรมตอนเข้าเกม", "ปิดแอปพื้นหลังที่ไม่จำเป็นอัตโนมัติ", ap.boostOnStart) { ap.boostOnStart = it }
    }

    GlassCard("AUTO-CLICKER (ช่วยกดอัตโนมัติ)", NeonCyan, "จำลองการแตะซ้ำๆ โดยไม่บล็อกการเลื่อนจอหรือกดปุ่มอื่น") {
        PrefSlider("ความเร็วการคลิก", ap.clickIntervalMs, 40..500, 10, " ms / ครั้ง") { ap.clickIntervalMs = it }
        Text(
            "เป้าหมาย 🎯 สีแดง/เขียว จะปรากฏบนหน้าจอเกมเมื่อเข้าเกม สามารถลากไปวางตรงจุดที่ต้องการกดได้ และเปิด/ปิดได้จากปุ่มลอยใน Toolbox ระหว่างเล่น",
            color = Color.LightGray, fontSize = 12.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val s = GameDetectorService.instance
                    if (s == null) Toast.makeText(ctx, "ต้องเปิด Auto-Apply Service ก่อน", Toast.LENGTH_LONG).show()
                    else s.startSession(forceToolbox = true)
                },
                modifier = Modifier.weight(1f)
            ) { Text("แสดงเป้าหมาย 🎯 ทดสอบ") }
            OutlinedButton(onClick = { GameDetectorService.instance?.stopSession() }, modifier = Modifier.weight(1f)) {
                Text("ซ่อนเป้าหมาย")
            }
        }
    }

    GlassCard("ระบบเตือนอัจฉริยะ", NeonRed, "เตือนผ่านปุ่มลอยในเกม") {
        PrefSlider("เตือนเครื่องร้อน (วัดที่แบต)", ap.heatAlertC, 35..50, 1, "°C") { ap.heatAlertC = it }
        PrefSlider("เตือนเน็ตแลคเมื่อ Ping เกิน", ap.pingAlertMs, 50..300, 25, " ms") { ap.pingAlertMs = it }
        PrefSlider("เตือนแบตต่ำกว่า", ap.batteryAlert, 5..30, 5, "%") { ap.batteryAlert = it }
        PrefSlider("เตือนพักสายตาทุก", ap.breakMin, 0..180, 15, " นาที", offAtZero = true) { ap.breakMin = it }
    }

    GlassCard("มาโครอัตโนมัติ", NeonPurple, "รันเองตอนเข้าหรือออกจากเกม") {
        MacroPicker("เมื่อเข้าเกม", vm.macros, ap.startMacroId) { ap.startMacroId = it }
        MacroPicker("เมื่อออกจากเกม", vm.macros, ap.exitMacroId) { ap.exitMacroId = it }
    }

    GlassCard("FLOATING TOOLBOX", NeonCyan, "ปุ่มลอยในเกม: แตะเพื่อเปิดแผง ลากเพื่อย้าย") {
        PrefToggle("แสดง Toolbox ตอนเข้าเกม", "สถานะ + ปุ่มลัด + มาโครที่ปักหมุด", ap.toolbox) { ap.toolbox = it }
        PrefToggle("แสดง FPS บนปุ่มลอย", "อ่านจาก SurfaceFlinger ทุก 2 วินาที", ap.toolboxFps) { ap.toolboxFps = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val s = GameDetectorService.instance
                    if (s == null) Toast.makeText(ctx, "ต้องเปิด Auto-Apply Service ก่อน", Toast.LENGTH_LONG).show()
                    else s.startSession(forceToolbox = true)
                },
                modifier = Modifier.weight(1f)
            ) { Text("ทดสอบ Toolbox") }
            OutlinedButton(onClick = { GameDetectorService.instance?.stopSession() }, modifier = Modifier.weight(1f)) {
                Text("ปิด Toolbox")
            }
        }
    }

    OptimizerCard(vm)
    HistoryCard(ap, g)
}

@Composable
private fun GameProfileCard(vm: MainViewModel) {
    val ctx = LocalContext.current
    val a11yOn = remember(vm.telemetry) { isA11yEnabled(ctx) }
    var dpiText by rememberSaveable { mutableStateOf(vm.gameDpi.toString()) }
    var pkgText by rememberSaveable { mutableStateOf(vm.gamePackages.joinToString(", ")) }

    GlassCard("โปรไฟล์เกม", NeonOrange, if (a11yOn) "Detector Service: ทำงาน" else "Detector Service: ยังไม่เปิด") {
        ToggleRow("Auto-Apply DPI เมื่อเปิดเกม", "ออกเกมแล้วคืน DPI เดิมให้อัตโนมัติ", vm.autoApply) { on ->
            vm.setAutoApplyOn(on)
            if (on && !a11yOn) {
                if (vm.hasShell) vm.enableA11y() else ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        OutlinedTextField(
            value = dpiText, onValueChange = { dpiText = it.filter(Char::isDigit).take(4) },
            label = { Text("DPI ขณะเล่นเกม") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = pkgText, onValueChange = { pkgText = it },
            label = { Text("Package เกม (คั่นด้วย , )") }, modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(onClick = { vm.saveGameProfile(dpiText, pkgText) }, modifier = Modifier.fillMaxWidth()) {
            Text("บันทึกโปรไฟล์เกม")
        }
        OutlinedButton(
            onClick = { vm.addForegroundGame() }, enabled = vm.hasShell,
            modifier = Modifier.fillMaxWidth()
        ) { Text("เพิ่มเกมที่เปิดอยู่ (กดแล้วสลับไปเปิดเกม รอ 5 วิ)") }
        ToggleRow(
            "ถือว่าทุกแอปเป็นเกม", "เมนูจะขึ้นกับทุกแอปที่เปิด (ยกเว้นหน้าจอหลัก/ระบบ/แอปนี้) ใช้เมื่อไม่อยากเพิ่มทีละเกม",
            vm.allApps
        ) { vm.applyAllApps(it) }
    }
}

@Composable
private fun MacroPicker(label: String, macros: List<Macro>, initialId: String?, onSelect: (String?) -> Unit) {
    var sel by remember { mutableStateOf(initialId) }
    var open by remember { mutableStateOf(false) }
    val name = macros.firstOrNull { it.id == sel }?.name ?: "ไม่ใช้"
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Color.White, fontSize = 13.sp)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(name) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text("ไม่ใช้") }, onClick = { sel = null; onSelect(null); open = false })
                macros.forEach { m ->
                    DropdownMenuItem(text = { Text(m.name) }, onClick = { sel = m.id; onSelect(m.id); open = false })
                }
            }
        }
    }
}

@Composable
private fun OptimizerCard(vm: MainViewModel) {
    val ctx = LocalContext.current
    val overlayOn by OverlayService.running.collectAsState()
    GlassCard("OPTIMIZER", NeonGreen, "ปรับระบบให้ตอบสนองไวขึ้น") {
        IntSlider("Pointer Speed", vm.pointerSpeed.roundToInt(), -7..7, 1, "", enabled = vm.hasShell) {
            vm.pointerSpeed = it.toFloat()
            vm.commitPointerSpeed()
        }
        ToggleRow("Touch Boost", "แอนิเมชัน 0.5x + Long-press 250ms", vm.touchBoost, vm.hasShell) { vm.setTouchBoostOn(it) }
        ToggleRow("Lock 90Hz", "บังคับ min/peak refresh rate = 90Hz", vm.lock90, vm.hasShell) { vm.setLock90On(it) }
        ToggleRow("Floating FPS/Ping Overlay", "หน้าต่างลอยแยก แสดงทับทุกแอป", overlayOn) { on ->
            if (on && !Settings.canDrawOverlays(ctx)) {
                if (vm.hasShell) vm.grantPermissions()
                else ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                return@ToggleRow
            }
            val i = Intent(ctx, OverlayService::class.java)
            if (on) ContextCompat.startForegroundService(ctx, i) else ctx.stopService(i)
        }
        GradientButton(
            if (vm.busy) "กำลังทำงาน..." else "RAM BOOSTER",
            listOf(Color(0xFF00C853), Color(0xFF00E676)), vm.hasShell && !vm.busy
        ) { vm.boostRam() }
    }
}

@Composable
private fun HistoryCard(ap: AssistPrefs, game: String?) {
    var version by remember { mutableIntStateOf(0) }
    val list = remember(version, game) { ap.history() }
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    GlassCard("ประวัติการเล่น", NeonCyan, "บันทึกอัตโนมัติเมื่อเล่นเกิน 1 นาที") {
        if (list.isEmpty()) Text("ยังไม่มีประวัติ", color = Color.Gray, fontSize = 13.sp)
        list.forEach { r ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TileDark).padding(12.dp)) {
                Row {
                    Text(gameName(r.game), color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Text(fmt.format(Date(r.startedAt)), color = Color.Gray, fontSize = 12.sp)
                }
                Text(
                    "${Assist.fmt(r.durationMs)}  •  FPS ${if (r.avgFps > 0) r.avgFps else "--"}  •  Ping ${r.avgPing}ms (สูงสุด ${r.maxPing})",
                    color = Color.LightGray, fontSize = 12.sp
                )
                Text("ร้อนสุด ${"%.1f".format(r.maxTemp)}°C  •  ใช้แบต ${r.batteryUsed}%", color = Color.LightGray, fontSize = 12.sp)
            }
        }
        if (list.isNotEmpty()) TextButton(onClick = { ap.clearHistory(); version++ }) { Text("ล้างประวัติ", color = NeonRed) }
    }
}

// ============================ MACRO SCREEN ============================
@Composable
fun MacroScreen(vm: MainViewModel) {
    var editing by remember { mutableStateOf<Macro?>(null) }
    val running by MacroEngine.running.collectAsState()

    GlassCard("MACRO", NeonPurple, "รวมหลายการตั้งค่าไว้ในปุ่มเดียว") {
        Text(
            "มาโครสั่งการตั้งค่าระบบ เช่น DPI, ห้ามรบกวน, RAM Boost และเปิดเกม รันได้จากแอป จากปุ่มลอยในเกม (ถ้าปักหมุด) หรือให้รันเองตอนเข้า/ออกเกมได้ที่แท็บผู้ช่วย",
            color = Color.LightGray, fontSize = 12.sp
        )
        GradientButton("+ สร้างมาโครใหม่", listOf(Color(0xFF7C4DFF), Color(0xFFB388FF))) {
            editing = Macro("m${System.currentTimeMillis()}", "มาโครใหม่", "boost\ndnd on\n")
        }
    }

    editing?.let { m ->
        MacroEditor(m, onSave = { vm.saveMacro(it); editing = null }, onCancel = { editing = null })
    }

    vm.macros.forEach { m ->
        MacroItem(
            m, running != null || !vm.hasShell,
            onRun = { vm.runMacro(m) },
            onEdit = { editing = m },
            onPin = { vm.togglePin(m.id) },
            onDelete = { vm.deleteMacro(m.id) }
        )
    }

    if (vm.macroLog.isNotEmpty()) {
        GlassCard("ผลการทำงาน", NeonGreen) {
            vm.macroLog.forEach { line ->
                Text(
                    line, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    color = when {
                        line.startsWith("✗") -> NeonRed
                        line.startsWith("✓") -> NeonGreen
                        else -> Color.LightGray
                    }
                )
            }
        }
    }
}

@Composable
private fun MacroItem(m: Macro, disabled: Boolean, onRun: () -> Unit, onEdit: () -> Unit, onPin: () -> Unit, onDelete: () -> Unit) {
    var confirmDel by remember(m.id) { mutableStateOf(false) }
    val steps = remember(m.script) { MacroEngine.parse(m.script).steps }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(TileDark).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("${steps.size} คำสั่ง", color = Color.Gray, fontSize = 11.sp)
            }
            Pill(if (m.pinned) "★ ปักหมุด" else "☆ ปักหมุด", m.pinned, NeonYellow, onPin)
        }
        Text(
            steps.take(4).joinToString("  →  ") { "${it.cmd} ${it.arg}".trim() },
            fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = NeonCyan,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onRun, enabled = !disabled, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF), contentColor = Color.White)
            ) { Text("▶ รัน") }
            OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("แก้ไข") }
            OutlinedButton(
                onClick = { if (confirmDel) onDelete() else confirmDel = true },
                modifier = Modifier.weight(1f)
            ) { Text(if (confirmDel) "ยืนยันลบ" else "ลบ", color = NeonRed) }
        }
    }
}

@Composable
private fun MacroEditor(initial: Macro, onSave: (Macro) -> Unit, onCancel: () -> Unit) {
    var name by remember(initial.id) { mutableStateOf(initial.name) }
    var script by remember(initial.id) { mutableStateOf(TextFieldValue(initial.script)) }
    var pinned by remember(initial.id) { mutableStateOf(initial.pinned) }
    val parsed = remember(script.text) { MacroEngine.parse(script.text) }

    GlassCard("แก้ไขมาโคร", NeonCyan, "บรรทัดละ 1 คำสั่ง • บรรทัดที่ขึ้นต้นด้วย # คือหมายเหตุ") {
        OutlinedTextField(
            value = name, onValueChange = { name = it.take(30) },
            label = { Text("ชื่อมาโคร") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = script, onValueChange = { script = it },
            label = { Text("คำสั่ง") }, minLines = 6,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Color.White),
            modifier = Modifier.fillMaxWidth()
        )
        if (parsed.errors.isEmpty()) {
            Text("✓ ${parsed.steps.size} คำสั่ง พร้อมใช้งาน", color = NeonGreen, fontSize = 12.sp)
        } else {
            parsed.errors.take(5).forEach { Text("✗ $it", color = NeonRed, fontSize = 12.sp) }
        }
        ToggleRow("ปักหมุดใน Toolbox", "แสดงเป็นปุ่มในแผงลอยระหว่างเล่นเกม", pinned) { pinned = it }
        Text("แตะคำสั่งเพื่อเพิ่ม:", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        MacroEngine.COMMANDS.forEach { (ex, desc) ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(TileDark)
                    .clickable {
                        val base = script.text.trimEnd()
                        val nt = if (base.isEmpty()) ex else "$base\n$ex"
                        script = TextFieldValue(nt, TextRange(nt.length))
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(ex, fontFamily = FontFamily.Monospace, color = NeonCyan, fontSize = 13.sp)
                Text(desc, color = Color.Gray, fontSize = 11.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { onSave(initial.copy(name = name.trim(), script = script.text.trim(), pinned = pinned)) },
                enabled = parsed.errors.isEmpty() && parsed.steps.isNotEmpty() && name.isNotBlank(),
                modifier = Modifier.weight(1f)
            ) { Text("บันทึก") }
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("ยกเลิก") }
        }
    }
}
