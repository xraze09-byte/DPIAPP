package com.dpi.gamingtweaker

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import kotlin.math.roundToInt

// ================= THEME =================
private val BgDark = Color(0xFF0B0B12)
private val CardDark = Color(0xFF161622)
internal val TileDark = Color(0xFF1E1E2C)
internal val NeonOrange = Color(0xFFFF7A00)
internal val NeonCyan = Color(0xFF00E5FF)
internal val NeonRed = Color(0xFFFF1744)
internal val NeonGreen = Color(0xFF00E676)
internal val NeonPurple = Color(0xFFB388FF)
internal val NeonYellow = Color(0xFFFFD600)
private val GamingColors = darkColorScheme(
    primary = NeonOrange, secondary = NeonCyan, background = BgDark, surface = CardDark, error = NeonRed
)

// ================= ACTIVITY =================
class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    private val binderListener = Shizuku.OnBinderReceivedListener { vm.refreshShellMode() }
    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> vm.refreshShellMode() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addRequestPermissionResultListener(permListener)
        setContent {
            MaterialTheme(colorScheme = GamingColors) { DpiApp(vm, ::requestShizuku) }
        }
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    private fun requestShizuku() {
        when {
            !Shell.shizukuRunning() ->
                toast("Shizuku ยังไม่ทำงาน — เปิดแอป Shizuku แล้วกด Start ก่อน (ถ้าใช้ Root ให้กด 'ตรวจใหม่')")
            Shizuku.isPreV11() -> toast("Shizuku เวอร์ชันเก่าเกินไป")
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> vm.refreshShellMode()
            else -> Shizuku.requestPermission(Shell.SHIZUKU_REQ)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}

// ================= VIEWMODEL =================
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val ctx get() = getApplication<Application>()

    var mode by mutableStateOf(Shell.Mode.NONE); private set
    var telemetry by mutableStateOf(Metrics.Telemetry()); private set
    var session by mutableStateOf<Tweaks.FailSafeSession?>(null); private set
    var countdown by mutableIntStateOf(0); private set
    var status by mutableStateOf("กำลังตรวจสอบสิทธิ์..."); private set
    var busy by mutableStateOf(false); private set

    var autoApply by mutableStateOf(prefs.autoApply); private set
    var gameDpi by mutableIntStateOf(prefs.gameDpi); private set
    var gamePackages by mutableStateOf(prefs.gamePackages.toList()); private set
    var pointerSpeed by mutableFloatStateOf(0f)
    var touchLevel by mutableIntStateOf(if (prefs.touchRestore != null) prefs.touchLevel.coerceAtLeast(1) else 0); private set
    var lock90 by mutableStateOf(prefs.refreshRestore != null); private set

    private val macroStore = MacroStore(app)
    var macros by mutableStateOf(macroStore.load()); private set
    val macroLog = mutableStateListOf<String>()

    /** HelpUi ใช้สวิตช์เปิด/ปิด; ของเรามี 3 ระดับ: เปิด = ระดับ "ไว" (1) */
    val touchBoost: Boolean get() = touchLevel > 0
    fun setTouchBoostOn(on: Boolean) = applyTouchLevel(if (on) 1 else 0)

    fun saveMacro(m: Macro) {
        val list = macros.toMutableList()
        val i = list.indexOfFirst { it.id == m.id }
        if (i >= 0) list[i] = m else list += m
        macros = list
        macroStore.save(list)
        status = "บันทึกมาโคร ${m.name}"
    }

    fun deleteMacro(id: String) {
        macros = macros.filter { it.id != id }
        macroStore.save(macros)
    }

    fun togglePin(id: String) {
        macros = macros.map { if (it.id == id) it.copy(pinned = !it.pinned) else it }
        macroStore.save(macros)
    }

    fun runMacro(m: Macro) {
        macroLog.clear()
        viewModelScope.launch {
            val ok = MacroEngine.run(ctx, m) { macroLog += it }
            status = if (ok) "มาโคร ${m.name} เสร็จ" else "มาโคร ${m.name} มีข้อผิดพลาด"
        }
    }

    val hasShell get() = mode != Shell.Mode.NONE
    private var timerJob: Job? = null

    init { refreshShellMode() }

    fun refreshShellMode() {
        viewModelScope.launch {
            mode = withContext(Dispatchers.IO) { Shell.detect(force = true) }
            status = when (mode) {
                Shell.Mode.SHIZUKU -> "เชื่อมต่อ Shizuku แล้ว"
                Shell.Mode.ROOT -> "เชื่อมต่อ Root แล้ว"
                Shell.Mode.NONE -> "ไม่พบ Shizuku/Root — ฟีเจอร์ระบบถูกปิด"
            }
            if (hasShell) Shell.exec("settings get system pointer_speed").out.toFloatOrNull()?.let { pointerSpeed = it }
        }
    }

    suspend fun telemetryLoop() {
        while (currentCoroutineContext().isActive) {
            runCatching { Metrics.collect(ctx, hasShell) }.onSuccess { telemetry = it }
            delay(2000)
        }
    }

    private fun guard(): Boolean = hasShell.also { if (!it) status = "ต้องเชื่อมต่อ Shizuku หรือ Root ก่อน" }

    // ---------- DPI / Resolution + Fail-Safe ----------
    fun applyDisplay(dpi: Int?, size: String?, label: String) {
        if (!guard() || session != null || busy) return
        if (dpi == null && size == null) { status = "ไม่มีค่าที่เปลี่ยนแปลง"; return }
        viewModelScope.launch {
            busy = true
            val s = Tweaks.applyWithFailSafe(dpi, size, FAILSAFE_SEC, label)
            busy = false
            if (s == null) { status = "ใช้ค่าไม่สำเร็จ (คืนค่าเดิมแล้ว)"; return@launch }
            countdown = FAILSAFE_SEC
            session = s
            timerJob = viewModelScope.launch {
                for (i in FAILSAFE_SEC downTo 1) { countdown = i; delay(1000) }
                countdown = 0
                revert(auto = true)
            }
        }
    }

    fun confirm() {
        val s = session ?: return
        timerJob?.cancel(); session = null
        viewModelScope.launch {
            val r = Tweaks.confirm(s)
            status = if (r.ok) "ยืนยันแล้ว: ${s.label}" else "ยืนยันไม่สำเร็จ — ระบบจะคืนค่าเดิมอัตโนมัติ"
        }
    }

    fun revert(auto: Boolean = false) {
        val s = session ?: return
        timerJob?.cancel(); session = null
        viewModelScope.launch {
            Tweaks.revert(s)
            status = if (auto) "หมดเวลา — คืนค่าเดิมอัตโนมัติแล้ว" else "คืนค่าเดิมแล้ว"
        }
    }

    fun emergencyReset() {
        if (!guard()) return
        timerJob?.cancel(); session = null
        viewModelScope.launch {
            val r = Tweaks.emergencyReset(prefs)
            if (r.ok) {
                touchLevel = 0; lock90 = false; pointerSpeed = 0f
                // คืน DND/แจ้งเตือน/ความสว่างที่ Game Mode เปลี่ยนไว้ด้วย
                val ap = AssistPrefs(ctx)
                GameDetectorService.instance?.stopSession()
                // ล้างสถานะ session เฉพาะเมื่อคืนค่าสำเร็จ ไม่งั้นปล่อยให้ detector ลองคืนซ้ำเอง
                if (GameMode.exit(ap).ok) { ap.sessionActive = false }
            }
            status = if (r.ok) "EMERGENCY RESET: คืนค่าโรงงานเรียบร้อย" else "รีเซ็ตล้มเหลว: ${r.err}"
        }
    }

    // ---------- Gaming Optimizer ----------
    private fun runTweak(label: String, onOk: () -> Unit = {}, block: suspend () -> Shell.Result) {
        if (!guard()) return
        viewModelScope.launch {
            val r = block()
            if (r.ok) onOk()
            status = if (r.ok) "OK: $label" else "ล้มเหลว: $label ${r.err}"
        }
    }

    fun commitPointerSpeed() {
        val v = pointerSpeed.roundToInt()
        runTweak("Pointer speed = $v") { Tweaks.setPointerSpeed(v) }
    }

    fun applyTouchLevel(level: Int) =
        runTweak(
            "Touch Boost ${listOf("OFF", "ไว", "ไวสุด")[level.coerceIn(0, 2)]}",
            onOk = { touchLevel = level; prefs.touchLevel = level }
        ) { Tweaks.touchBoost(level, prefs) }

    fun setLock90On(on: Boolean) =
        runTweak("Lock 90Hz ${if (on) "ON" else "OFF"}", onOk = { lock90 = on }) {
            Tweaks.lockRefreshRate(if (on) 90 else null, prefs)
        }

    fun boostRam() {
        if (!guard() || busy) return
        viewModelScope.launch {
            busy = true
            val before = Metrics.ram(ctx).second
            val n = Tweaks.boostRam(prefs.gamePackages + ctx.packageName)
            delay(1000)
            val after = Metrics.ram(ctx).second
            busy = false
            status = "RAM Booster: เคลียร์ $n แอป | คืน RAM ~${(after - before).coerceAtLeast(0)} MB"
        }
    }

    fun grantPermissions() = runTweak("Grant สิทธิ์ระบบ + Overlay (กดเปิดอีกครั้งได้เลย)") {
        Tweaks.selfGrant(ctx.packageName)
    }

    // ---------- Auto-Apply / Launch ----------
    fun setAutoApplyOn(on: Boolean) { autoApply = on; prefs.autoApply = on }

    fun enableA11y() = runTweak("เปิด Auto-Apply Service") {
        Tweaks.enableAccessibility("${ctx.packageName}/${ctx.packageName}.GameDetectorService")
    }

    fun launchGame(pkg: String) {
        if (!guard() || busy || session != null) return
        viewModelScope.launch {
            busy = true
            val r = Tweaks.launchGame(prefs, pkg)
            busy = false
            status = when {
                !r.ok -> "เปิดเกมไม่สำเร็จ: ${r.err}"
                isA11yEnabled(ctx) -> "เปิด $pkg @ ${prefs.gameDpi} DPI — ออกจากเกมแล้วจะคืนค่าเอง"
                else -> "เปิด $pkg @ ${prefs.gameDpi} DPI — เปิด Auto-Apply Service ไว้ด้วย จะได้คืนค่าเองตอนออกเกม"
            }
        }
    }

    var allApps by mutableStateOf(prefs.allApps); private set
    fun applyAllApps(on: Boolean) { prefs.allApps = on; allApps = on }

    /** เพิ่มแอปที่อยู่หน้าจอตอนนี้เข้ารายชื่อเกม (สลับไปเกมแล้วเปิดแอปนี้กลับมากดไม่ได้ จึงนับถอยหลัง) */
    fun addForegroundGame() {
        viewModelScope.launch {
            status = "สลับไปเปิดเกมภายใน 5 วินาที..."
            delay(5000)
            val pkg = withContext(Dispatchers.IO) { Tweaks.foregroundPackage(ctx.packageName) }
            if (pkg == null || !Tweaks.PKG_RE.matches(pkg)) { status = "ไม่พบแอปที่เปิดอยู่ ลองใหม่และอยู่ในเกมให้ครบ 5 วินาที"; return@launch }
            val pk = prefs.gamePackages + pkg
            prefs.gamePackages = pk; gamePackages = pk.toList()
            status = "เพิ่มเกม $pkg แล้ว (รวม ${pk.size} เกม)"
        }
    }

    fun saveGameProfile(scaleText: String, pkgText: String) {
        val factory = shortSidePx(telemetry.resolution) ?: run { status = "ยังอ่านความละเอียดจอไม่ได้ เชื่อม Shizuku/Root ก่อน"; return }
        val sc = parseScale(scaleText) ?: run { status = "ตัวเลขเกมต้องอยู่ระหว่าง $SCALE_MIN–$SCALE_MAX"; return }
        val d = scaleToDpi(sc, factory)
        val pk = pkgText.split(',', ' ', '\n').map { it.trim() }.filter { Tweaks.PKG_RE.matches(it) }.toSet()
        if (pk.isEmpty()) { status = "ไม่มี package name ที่ถูกต้อง"; return }
        prefs.gameDpi = d; prefs.gamePackages = pk
        gameDpi = d; gamePackages = pk.toList()
        status = "บันทึกโปรไฟล์: ${pk.size} เกม @ $d DPI"
    }

    companion object {
        const val DPI_MIN = 120
        const val DPI_MAX = 1200
        const val FAILSAFE_SEC = 10
        private val SIZE_RE = Regex("""(\d{3,4})x(\d{3,4})""")

        fun parseDpi(t: String): Int? = t.trim().toIntOrNull()?.takeIf { it in DPI_MIN..DPI_MAX }

        // Scale กลับด้านกับ DPI: Scale สูง = UI เล็ก, Scale ต่ำ = UI ใหญ่ (Scale 100 = ค่าโรงงาน)
        // Smallest width (dp) แบบเดียวกับเมนูนักพัฒนา: dp = ด้านสั้นของจอ(px) * 160 / DPI
        // เลขสูง = เล็ก, เลขต่ำ = ใหญ่  (พารามิเตอร์ที่ 2 คือความกว้างจอเป็นพิกเซล ไม่ใช่ DPI โรงงานอีกต่อไป)
        const val SCALE_MIN = 200
        const val SCALE_MAX = 1200
        fun dpiToScale(dpi: Int, widthPx: Int): Int = (widthPx * 160f / dpi).roundToInt()
        fun scaleToDpi(dp: Int, widthPx: Int): Int =
            (widthPx * 160f / dp).roundToInt().coerceIn(DPI_MIN, DPI_MAX)
        /** ด้านสั้นของความละเอียด เช่น "1080x2400" -> 1080 */
        fun shortSidePx(res: String?): Int? =
            res?.split("x")?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }?.min()
        fun parseScale(t: String): Int? = t.trim().toIntOrNull()?.takeIf { it in SCALE_MIN..SCALE_MAX }
        fun parseSize(t: String): String? {
            val m = SIZE_RE.matchEntire(t.trim().lowercase()) ?: return null
            val (w, h) = m.destructured
            return if (w.toInt() in 480..4320 && h.toInt() in 480..4320) "${w}x$h" else null
        }
    }
}

fun isA11yEnabled(ctx: Context): Boolean =
    Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?.contains("${ctx.packageName}/") == true

// ================= ROOT UI =================
@Composable
fun DpiApp(vm: MainViewModel, onRequestShizuku: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.telemetryLoop() }
    }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf("หน้าหลัก", "ผู้ช่วย", "มาโคร")

    Scaffold(
        containerColor = BgDark,
        bottomBar = { EmergencyResetBar(vm.hasShell, vm.telemetry.physicalDpi, vm::emergencyReset) }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            TabRow(
                selectedTabIndex = tab,
                containerColor = BgDark,
                contentColor = NeonCyan
            ) {
                tabs.forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontWeight = FontWeight.Bold) })
                }
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                when (tab) {
                    0 -> {
                        Header(vm, onRequestShizuku)
                        DashboardCard(vm.telemetry)
                        DpiControllerCard(vm)
                        PresetsSection(
                            enabled = vm.hasShell && vm.session == null && !vm.busy,
                            factoryDpi = vm.telemetry.physicalDpi,
                            widthPx = MainViewModel.shortSidePx(vm.telemetry.resolution)
                        ) { p, dpi -> vm.applyDisplay(dpi, null, "${p.name} ($dpi DPI)") }
                        OptimizerCard(vm)
                        AutoApplyCard(vm)
                    }
                    1 -> AssistScreen(vm)
                    else -> MacroScreen(vm)
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    vm.session?.let { s -> FailSafeDialog(s.label, vm.countdown, vm::confirm) { vm.revert() } }
}

// ================= COMPONENTS =================
@Composable
internal fun SectionCard(title: String, accent: Color = NeonOrange, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
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
private fun Header(vm: MainViewModel, onRequestShizuku: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("DPI GAMING TWEAKER", fontSize = 26.sp, fontWeight = FontWeight.Black, color = NeonOrange)
        Text(
            "Backend: ${vm.mode}",
            color = if (vm.hasShell) NeonGreen else NeonRed,
            fontFamily = FontFamily.Monospace, fontSize = 12.sp
        )
        Text(vm.status, color = Color.LightGray, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!vm.hasShell) {
                Button(onClick = onRequestShizuku) { Text("ขอสิทธิ์ Shizuku") }
                OutlinedButton(onClick = vm::refreshShellMode) { Text("ตรวจใหม่ / Root") }
            } else {
                OutlinedButton(onClick = { vm.grantPermissions() }) { Text("Grant สิทธิ์ระบบ") }
            }
        }
    }
}

@Composable
private fun RowScope.StatTile(label: String, value: String, sub: String? = null, color: Color = Color.White) {
    Column(Modifier.weight(1f).background(TileDark, RoundedCornerShape(14.dp)).padding(12.dp)) {
        Text(label, color = Color.Gray, fontSize = 11.sp)
        Text(value, color = color, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        sub?.let { Text(it, color = Color.Gray, fontSize = 10.sp) }
    }
}

private fun tempColor(t: Float?) = when {
    t == null -> Color.White; t < 40f -> NeonGreen; t < 48f -> NeonYellow; else -> NeonRed
}

private fun pingColor(p: Int?) = when {
    p == null -> Color.White; p < 60 -> NeonGreen; p < 120 -> NeonYellow; else -> NeonRed
}

@Composable
private fun DashboardCard(t: Metrics.Telemetry) {
    SectionCard("SYSTEM DASHBOARD", NeonCyan) {
        val rowMod = Modifier.fillMaxWidth()
        val gap = Arrangement.spacedBy(10.dp)
        Row(rowMod, gap) {
            StatTile(
                "Smallest width dp",
                run { val w = MainViewModel.shortSidePx(t.resolution); if (t.activeDpi != null && w != null) MainViewModel.dpiToScale(t.activeDpi, w).toString() else "--" },
                "DPI ${t.activeDpi ?: "--"} / โรงงาน ${t.physicalDpi ?: "--"}", NeonOrange
            )
            StatTile("Resolution", t.resolution, t.physicalRes?.let { "Native $it" })
        }
        Row(rowMod, gap) {
            val ramLow = t.ramTotalMb > 0 && t.ramAvailMb.toFloat() / t.ramTotalMb < 0.15f
            StatTile("RAM ว่าง", "${t.ramAvailMb} MB", "จาก ${t.ramTotalMb} MB", if (ramLow) NeonRed else NeonGreen)
            StatTile("${t.tempSource} Temp", t.cpuTempC?.let { "%.1f°C".format(it) } ?: "--", null, tempColor(t.cpuTempC))
        }
        Row(rowMod, gap) {
            StatTile("Ping", t.pingMs?.let { "$it ms" } ?: "--", "8.8.8.8:53", pingColor(t.pingMs))
            StatTile("Refresh", "${t.refreshHz.roundToInt()} Hz", null, if (t.refreshHz >= 89f) NeonGreen else Color.White)
        }
        if (t.ramTotalMb > 0) {
            LinearProgressIndicator(
                progress = { 1f - t.ramAvailMb.toFloat() / t.ramTotalMb },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                color = NeonCyan, trackColor = TileDark
            )
        }
        val dims = t.resolution.split("x").mapNotNull { it.toIntOrNull() }
        val fhdPlus = dims.size == 2 && dims.min() == 1080 && dims.max() > 1920
        val match = fhdPlus && t.refreshHz >= 89f
        Text(
            if (match) "ตรงกับโปรไฟล์ทดสอบ FHD+ 90Hz"
            else "โปรไฟล์ทดสอบ: FHD+ 90Hz (เครื่องนี้ ${t.resolution} @ ${t.refreshHz.roundToInt()}Hz)",
            color = if (match) NeonGreen else Color.Gray, fontSize = 12.sp
        )
    }
}

@Composable
private fun DpiControllerCard(vm: MainViewModel) {
    var scaleText by rememberSaveable { mutableStateOf("") }
    var sizeText by rememberSaveable { mutableStateOf("") }
    val active = vm.telemetry.activeDpi
    val factoryDpi = vm.telemetry.physicalDpi
    val factory = MainViewModel.shortSidePx(vm.telemetry.resolution) // ความกว้างจอ px
    val curScale = if (active != null && factory != null) MainViewModel.dpiToScale(active, factory) else null
    val base0 = if (factoryDpi != null && factory != null) MainViewModel.dpiToScale(factoryDpi, factory) else 360
    LaunchedEffect(curScale) { if (scaleText.isBlank() && curScale != null) scaleText = curScale.toString() }

    val scaleVal = MainViewModel.parseScale(scaleText)
    val sizeVal = MainViewModel.parseSize(sizeText)
    val scaleErr = scaleText.isNotBlank() && scaleVal == null
    val sizeErr = sizeText.isNotBlank() && sizeVal == null
    val targetDpi = if (scaleVal != null && factory != null) MainViewModel.scaleToDpi(scaleVal, factory) else null

    SectionCard("SMALLEST WIDTH (dp) & RESOLUTION") {
        Text("Smallest width (dp) เหมือนเมนูนักพัฒนา: เลขสูง = เล็ก, เลขต่ำ = ใหญ่  |  ค่าโรงงาน = $base0", color = Color.Gray, fontSize = 11.sp)
        OutlinedTextField(
            value = scaleText,
            onValueChange = { scaleText = it.filter(Char::isDigit).take(3) },
            label = { Text("Smallest width (dp) ${MainViewModel.SCALE_MIN}–${MainViewModel.SCALE_MAX}") },
            isError = scaleErr,
            supportingText = {
                Text(
                    when {
                        factory == null -> "เชื่อม Shizuku/Root เพื่ออ่านความละเอียดจอ"
                        targetDpi != null -> "= DPI จริง $targetDpi (โรงงาน ${factoryDpi ?: "--"}, ตอนนี้ ${active ?: "--"})"
                        else -> "ใส่ตัวเลข ${MainViewModel.SCALE_MIN}–${MainViewModel.SCALE_MAX}"
                    }
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Slider(
            value = (scaleVal ?: curScale ?: base0).toFloat().coerceIn(base0 * 0.6f, base0 * 1.8f),
            onValueChange = { scaleText = it.roundToInt().toString() },
            valueRange = (base0 * 0.6f)..(base0 * 1.8f)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(-10, -1, 1, 10).forEach { d ->
                OutlinedButton(
                    onClick = {
                        val base = scaleVal ?: curScale ?: base0
                        scaleText = (base + d).coerceIn(MainViewModel.SCALE_MIN, MainViewModel.SCALE_MAX).toString()
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(0.dp)
                ) { Text(if (d > 0) "+$d" else "$d", fontFamily = FontFamily.Monospace) }
            }
        }
        OutlinedTextField(
            value = sizeText,
            onValueChange = { sizeText = it.lowercase().filter { c -> c.isDigit() || c == 'x' }.take(9) },
            label = { Text("Resolution เช่น 1080x2400 (เว้นว่าง = ไม่เปลี่ยน)") },
            isError = sizeErr,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                val newDpi = targetDpi?.takeIf { it != active }
                val label = listOfNotNull(scaleVal?.let { "$it (DPI จริง ${newDpi ?: active})" }, sizeVal).joinToString(" + ")
                vm.applyDisplay(newDpi, sizeVal, label)
            },
            enabled = vm.hasShell && vm.session == null && !vm.busy && !scaleErr && !sizeErr &&
                ((targetDpi != null && targetDpi != active) || sizeVal != null),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(14.dp)
        ) { Text("APPLY  (Fail-Safe ${MainViewModel.FAILSAFE_SEC}s)", fontWeight = FontWeight.Bold) }
    }
}

/** shrinkPct = เล็กลงกี่ % จากค่าโรงงาน (DPI ต่ำ = UI เล็กลง, DPI สูง = UI ใหญ่ขึ้น) */
data class Preset(val name: String, val shrinkPct: Int, val tag: String, val colors: List<Color>) {
    fun dpiFor(factory: Int): Int =
        (factory * (100 - shrinkPct) / 100f).roundToInt().coerceIn(MainViewModel.DPI_MIN, MainViewModel.DPI_MAX)
}

private val PRESETS = listOf(
    Preset("เล็กลง 10%", 10, "เห็นเพิ่มนิดหน่อย", listOf(Color(0xFF00C853), Color(0xFF64DD17))),
    Preset("เล็กลง 20%", 20, "แนะนำสำหรับเล่นเกม", listOf(Color(0xFFFF7A00), Color(0xFFFF1744))),
    Preset("เล็กลง 30%", 30, "เห็นพื้นที่เยอะ", listOf(Color(0xFF0091EA), Color(0xFF00E5FF))),
    Preset("เล็กลง 40%", 40, "เล็กมาก ตัวหนังสืออาจอ่านยาก", listOf(Color(0xFF7C4DFF), Color(0xFFFF4081))),
)

@Composable
private fun PresetsSection(enabled: Boolean, factoryDpi: Int?, widthPx: Int?, onApply: (Preset, Int) -> Unit) {
    SectionCard("MACRO PRESETS (เทียบกับค่าโรงงาน ${factoryDpi ?: "--"})", NeonPurple) {
        Text("ตัวเลขใหญ่บนปุ่ม = Smallest width (dp) สูง = เล็ก ต่ำ = ใหญ่ ส่วน DPI จริงอยู่บรรทัดล่าง", color = Color.Gray, fontSize = 11.sp)
        PRESETS.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { p ->
                    val target = factoryDpi?.let { p.dpiFor(it) }
                    val ok = enabled && target != null
                    Box(
                        Modifier.weight(1f).height(112.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Brush.linearGradient(if (ok) p.colors else listOf(Color.DarkGray, Color.Gray)))
                            .clickable(enabled = ok) { target?.let { onApply(p, it) } }
                            .padding(14.dp)
                    ) {
                        Column(Modifier.align(Alignment.BottomStart)) {
                            Text(widthPx?.let { w -> target?.let { MainViewModel.dpiToScale(it, w).toString() } } ?: "--", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Color.White)
                            Text(p.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            Text("${p.tag} · DPI ${target ?: "--"}", fontSize = 11.sp, color = Color.White.copy(alpha = .8f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ToggleRow(title: String, sub: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Color.Gray, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun OptimizerCard(vm: MainViewModel) {
    val ctx = LocalContext.current
    val overlayOn by OverlayService.running.collectAsState()

    SectionCard("GAMING OPTIMIZER", NeonGreen) {
        Text("Pointer Speed: ${vm.pointerSpeed.roundToInt()}", color = Color.White, fontWeight = FontWeight.SemiBold)
        Slider(
            value = vm.pointerSpeed,
            onValueChange = { vm.pointerSpeed = it },
            onValueChangeFinished = vm::commitPointerSpeed,
            valueRange = -7f..7f, steps = 13, enabled = vm.hasShell
        )
        Text("Touch Boost: " + listOf("ปิด", "ไว", "ไวสุด")[vm.touchLevel], color = Color.White, fontWeight = FontWeight.SemiBold)
        Text(
            "ไว = แอนิเมชัน 0.5x | ไวสุด = ปิดแอนิเมชัน + แตะซ้ำไวขึ้น (ลด Long-press 250ms ทั้งสองระดับ)",
            color = Color.Gray, fontSize = 11.sp
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ปิด", "ไว", "ไวสุด").forEachIndexed { i, label ->
                val sel = vm.touchLevel == i
                Button(
                    onClick = { vm.applyTouchLevel(i) },
                    enabled = vm.hasShell,
                    modifier = Modifier.weight(1f),
                    colors = if (sel) ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = Color.Black)
                    else ButtonDefaults.buttonColors(containerColor = TileDark, contentColor = Color.White)
                ) { Text(label, fontWeight = FontWeight.Bold) }
            }
        }
        Text("ไวขึ้นอีกด้วยการเพิ่ม Pointer Speed ด้านบน (ลองที่ 3-5 ก่อน)", color = Color.Gray, fontSize = 11.sp)
        ToggleRow("Lock 90Hz", "บังคับ min/peak refresh rate = 90Hz", vm.lock90, vm.hasShell) { vm.setLock90On(it) }
        ToggleRow("Floating FPS/Ping Overlay", "แสดงทับทุกแอป รวม split-screen", overlayOn, true) { on ->
            if (on && !Settings.canDrawOverlays(ctx)) {
                if (vm.hasShell) vm.grantPermissions()
                else ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                )
                return@ToggleRow
            }
            val i = Intent(ctx, OverlayService::class.java)
            if (on) ContextCompat.startForegroundService(ctx, i) else ctx.stopService(i)
        }
        Button(
            onClick = vm::boostRam,
            enabled = vm.hasShell && !vm.busy,
            modifier = Modifier.fillMaxWidth().height(60.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = Color.Black)
        ) { Text(if (vm.busy) "กำลังทำงาน..." else "RAM BOOSTER", fontWeight = FontWeight.Black, fontSize = 18.sp) }
    }
}

@Composable
private fun AutoApplyCard(vm: MainViewModel) {
    val ctx = LocalContext.current
    val a11yOn = remember(vm.telemetry) { isA11yEnabled(ctx) }
    val widthPx = MainViewModel.shortSidePx(vm.telemetry.resolution)
    val gameScale = widthPx?.let { MainViewModel.dpiToScale(vm.gameDpi, it) }
    var dpiText by rememberSaveable(gameScale) { mutableStateOf(gameScale?.toString() ?: "450") }
    var pkgText by rememberSaveable { mutableStateOf(vm.gamePackages.joinToString(", ")) }

    SectionCard("AUTO-APPLY PROFILE", NeonCyan) {
        ToggleRow(
            "Auto-Apply เมื่อเปิดเกม",
            if (a11yOn) "Detector Service: ทำงาน" else "Detector Service: ยังไม่เปิด",
            vm.autoApply, true
        ) { on ->
            vm.setAutoApplyOn(on)
            if (on && !a11yOn) {
                if (vm.hasShell) vm.enableA11y()
                else ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        Text(
            "แนะนำสำหรับ Free Fire: กดปุ่มเปิดเกมด้านล่าง แอปจะตั้ง DPI ก่อนแล้วค่อยเปิดเกม เกมจะไม่รีสตาร์ต",
            color = Color.Gray, fontSize = 11.sp
        )
        vm.gamePackages.forEach { pkg ->
            Button(
                onClick = { vm.launchGame(pkg) },
                enabled = vm.hasShell && !vm.busy && vm.session == null,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NeonOrange, contentColor = Color.Black)
            ) { Text("เปิดเกม @ ${gameScale ?: "--"} dp (DPI จริง ${vm.gameDpi})\n$pkg", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        }
        OutlinedTextField(
            value = dpiText,
            onValueChange = { dpiText = it.filter(Char::isDigit).take(4) },
            label = { Text("Smallest width (dp) ขณะเล่นเกม (สูง = เล็ก)") },
            supportingText = {
                val sc = MainViewModel.parseScale(dpiText)
                Text(if (sc != null && widthPx != null) "= DPI จริง ${MainViewModel.scaleToDpi(sc, widthPx)}" else "เช่น 450")
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = pkgText,
            onValueChange = { pkgText = it },
            label = { Text("Package เกม (คั่นด้วย , )") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(onClick = { vm.saveGameProfile(dpiText, pkgText) }, modifier = Modifier.fillMaxWidth()) {
            Text("บันทึกโปรไฟล์เกม")
        }
    }
}

@Composable
private fun EmergencyResetBar(enabled: Boolean, factoryDpi: Int?, onReset: () -> Unit) {
    Surface(color = BgDark) {
        Button(
            onClick = onReset,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp).height(80.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = NeonRed, contentColor = Color.White)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("EMERGENCY RESET", fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Text("คืนค่าโรงงานทันที${factoryDpi?.let { " ($it DPI)" } ?: ""}", fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun FailSafeDialog(label: String, seconds: Int, onConfirm: () -> Unit, onRevert: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        containerColor = CardDark,
        title = { Text("ยืนยันการตั้งค่าใหม่?", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("ใช้งาน: $label")
                Text("คืนค่าเดิมอัตโนมัติใน $seconds วินาที", color = NeonRed, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                LinearProgressIndicator(
                    progress = { seconds / MainViewModel.FAILSAFE_SEC.toFloat() },
                    modifier = Modifier.fillMaxWidth(), color = NeonRed
                )
                Text("ถ้าจอค้างหรือดำ ไม่ต้องทำอะไร ระบบจะย้อนค่าให้เอง", fontSize = 12.sp, color = Color.Gray)
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = NeonGreen, contentColor = Color.Black)) {
                Text("CONFIRM", fontWeight = FontWeight.Black)
            }
        },
        dismissButton = { OutlinedButton(onClick = onRevert) { Text("REVERT") } }
    )
}

internal fun gameName(pkg: String): String = when (pkg) {
    "com.dts.freefireth" -> "Free Fire"
    "com.dts.freefiremax" -> "Free Fire MAX"
    else -> pkg.substringAfterLast('.')
}

@Composable
internal fun Pill(text: String, selected: Boolean, color: Color = NeonOrange, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) Color.Black else Color.White,
        fontSize = 12.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) color else Color.White.copy(alpha = .1f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    )
}

@Composable
internal fun GradientButton(text: String, colors: List<Color>, enabled: Boolean = true, height: Dp = 56.dp, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(16.dp))
            .background(Brush.horizontalGradient(if (enabled) colors else listOf(Color.DarkGray, Color.Gray)))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Text(text, color = Color.White, fontWeight = FontWeight.Black, fontSize = 15.sp, textAlign = TextAlign.Center) }
}
