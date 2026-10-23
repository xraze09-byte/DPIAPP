package com.dpi.gamingtweaker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

// ============================ TARGET DATA ============================
/** จุดเป้าหมาย 1 จุด: ตำแหน่งมุมซ้ายบนของวงกลมบนจอ (px) และเปิด/ปิดแยกกัน */
data class ClickTarget(val id: Int, var x: Int, var y: Int, var enabled: Boolean = true)

fun AssistPrefs.targets(): MutableList<ClickTarget> {
    val list = runCatching {
        val arr = JSONArray(targetsJson ?: "[]")
        MutableList(arr.length()) {
            val o = arr.getJSONObject(it)
            ClickTarget(o.optInt("id", it + 1), o.optInt("x", 500), o.optInt("y", 1000), o.optBoolean("on", true))
        }
    }.getOrDefault(mutableListOf())
    // รองรับค่าเดิมจากเวอร์ชันก่อน: ใช้ตำแหน่งเป้าเดียวเป็น Target 1
    if (list.isEmpty()) list += ClickTarget(1, clickX, clickY)
    return list
}

fun AssistPrefs.saveTargets(list: List<ClickTarget>) {
    targetsJson = JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("x", it.x).put("y", it.y).put("on", it.enabled)) }
    }.toString()
}

fun AssistPrefs.clickModeLabel() = when (clickMode) { 1 -> "HOLD"; 2 -> "DOUBLE"; else -> "TAP" }

// ============================ ENGINE + OVERLAYS ============================
/**
 * ระบบ Auto-Click แบบ Multi-Target + Trigger
 * - Target N = วงกลมลากวางได้อิสระ (เพิ่ม/ลบได้) คือจุดที่ระบบจะแตะ
 * - Trigger = ปุ่มลอยสั่งยิง: กดค้างเพื่อยิง (หรือโหมดแตะสลับ) ตามความไว/กันแตะพลาดที่ตั้งไว้
 * ใช้ API เดิม (show/hide/toggle/start/stop/isRunning) เพื่อให้ Toolbox และ Service ไม่ต้องเปลี่ยน
 */
class AutoClickerTarget(
    private val ctx: Context,
    private val wm: WindowManager,
    private val ap: AssistPrefs,
    private val service: AccessibilityService,
) {
    private val unit = DisplayMetrics.DENSITY_DEVICE_STABLE / 160f
    private fun dp(v: Int) = (v * unit).roundToInt()

    private class TView(val t: ClickTarget, val view: TextView, val lp: WindowManager.LayoutParams)

    private val views = mutableListOf<TView>()
    private var trigger: TextView? = null
    private var trigParams: WindowManager.LayoutParams? = null
    private var clickJob: Job? = null
    private var holdJob: Job? = null
    private var editing = false          // true = จัดวางได้ (ปลดล็อก), false = ล็อกตำแหน่ง
    private val MAX_RUN_MS = 10 * 60_000L

    var isRunning = false
        private set
    val shown: Boolean get() = trigger != null
    val targetCount: Int get() = views.size

    // ---------- สร้าง/ลบ overlay ----------
    private fun lpFor(w: Int, h: Int, x: Int, y: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x; this.y = y
        if (Build.VERSION.SDK_INT >= 28)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun circle(fill: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(dp(2), stroke)
    }

    private fun alpha() = if (ap.triggerHidden) 0.12f else 1f

    fun show() {
        if (trigger != null) return
        val list = ap.targets()
        list.forEach { addTargetView(it) }
        addTrigger()
        refreshLook()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addTargetView(t: ClickTarget) {
        val sz = dp(48)
        val v = TextView(ctx).apply {
            text = "${t.id}"
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * unit)
            gravity = Gravity.CENTER
        }
        val p = lpFor(sz, sz, t.x, t.y, touchable = true)
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        v.setOnTouchListener { _, e ->
            // กันแตะพลาด: ล็อกตำแหน่งไว้ ลากได้เฉพาะตอนปลดล็อก (🔓)
            if (!editing || isRunning) return@setOnTouchListener true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = p.x; sy = p.y; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    p.x = sx + (e.rawX - tx).toInt(); p.y = sy + (e.rawY - ty).toInt()
                    runCatching { wm.updateViewLayout(v, p) }
                }
                MotionEvent.ACTION_UP -> { t.x = p.x; t.y = p.y; persist() }
            }
            true
        }
        runCatching { wm.addView(v, p) }.onSuccess { views += TView(t, v, p) }
            .onFailure { GameDetectorService.lastDecision.value = "สร้างเป้า ${t.id} ไม่สำเร็จ: ${it.javaClass.simpleName}" }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addTrigger() {
        val size = dp(ap.triggerSize.coerceIn(36, 120))
        val v = TextView(ctx).apply {
            text = "FIRE"
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.DEFAULT_BOLD
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 13f * unit)
            gravity = Gravity.CENTER
        }
        val p = lpFor(size, size, ap.trigX, ap.trigY, touchable = true)
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        var downAt = 0L; var armed = false; var moved = false
        v.setOnTouchListener { _, e ->
            if (editing) {
                // โหมดจัดวาง: ลากทริกเกอร์ไปวาง
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = p.x; sy = p.y; tx = e.rawX; ty = e.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = sx + (e.rawX - tx).toInt(); p.y = sy + (e.rawY - ty).toInt()
                        runCatching { wm.updateViewLayout(v, p) }
                    }
                    MotionEvent.ACTION_UP -> { ap.trigX = p.x; ap.trigY = p.y }
                }
                return@setOnTouchListener true
            }
            val need = if (ap.accidentalGuard) maxOf(ap.triggerHoldMs, 250) else ap.triggerHoldMs
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAt = SystemClock.uptimeMillis(); armed = false; moved = false
                    tx = e.rawX; ty = e.rawY
                    if (!ap.triggerToggle) {
                        // กดค้างเพื่อยิง: รอให้ถึงเวลาที่กำหนดค่อยเริ่ม
                        holdJob?.cancel()
                        holdJob = CoroutineScope(Dispatchers.Main).launch {
                            if (need > 0) delay(need.toLong())
                            armed = true
                            start(CoroutineScope(Dispatchers.Main))
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> if (abs(e.rawX - tx) > dp(24) || abs(e.rawY - ty) > dp(24)) moved = true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    holdJob?.cancel()
                    if (ap.triggerToggle) {
                        // โหมดแตะสลับ: กันแตะพลาดด้วยการต้องกดนานพอ และไม่ขยับนิ้ว
                        val heldMs = SystemClock.uptimeMillis() - downAt
                        if (e.actionMasked == MotionEvent.ACTION_UP && !moved && heldMs >= need) {
                            toggle(CoroutineScope(Dispatchers.Main))
                        }
                    } else if (armed || isRunning) stop()
                }
            }
            true
        }
        runCatching { wm.addView(v, p) }.onSuccess { trigger = v; trigParams = p }
            .onFailure { GameDetectorService.lastDecision.value = "สร้างทริกเกอร์ไม่สำเร็จ: ${it.javaClass.simpleName}" }
    }

    private fun persist() = ap.saveTargets(views.map { it.t })

    /** อัปเดตสี/ความโปร่งใส/สถานะล็อกของทุกอย่างบนจอ */
    fun refreshLook() {
        val a = alpha()
        views.forEach { tv ->
            val on = tv.t.enabled
            tv.view.background = circle(
                if (isRunning && on) 0x8800E676.toInt() else if (on) 0x88FF1744.toInt() else 0x55607D8B.toInt(),
                if (editing) 0xFF00E5FF.toInt() else 0xFFFFD600.toInt()
            )
            tv.view.alpha = a
            // ระหว่างทำงานหรือไม่ได้จัดวาง ให้ทัชทะลุเป้า ไม่ดักนิ้วที่เล่นเกม
            setTouchable(tv.view, tv.lp, editing && !isRunning)
        }
        trigger?.let { t ->
            t.alpha = a
            t.text = when {
                editing -> "ย้าย"
                isRunning -> "ยิง"
                ap.triggerToggle -> "แตะ"
                else -> "FIRE"
            }
            t.background = circle(
                if (isRunning) 0xCC00C853.toInt() else 0xAA2962FF.toInt(),
                if (ap.accidentalGuard) 0xFFFFD600.toInt() else 0xFFFFFFFF.toInt()
            )
        }
    }

    private fun setTouchable(v: View, p: WindowManager.LayoutParams, touchable: Boolean) {
        p.flags = if (touchable) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(v, p) }
    }

    /** ใช้ขนาดทริกเกอร์ใหม่ (Triggers Sensitivity) โดยสร้างทริกเกอร์ใหม่ที่เดิม */
    fun rebuildTrigger() {
        val t = trigger ?: return
        runCatching { wm.removeView(t) }
        trigger = null; trigParams = null
        addTrigger(); refreshLook()
    }

    // ---------- จัดการ Target ----------
    fun addTarget(): Int {
        val list = views.map { it.t }
        val id = (list.maxOfOrNull { it.id } ?: 0) + 1
        val off = dp(56) * (list.size % 6)
        val t = ClickTarget(id, ap.clickX + off, ap.clickY)
        if (trigger != null) addTargetView(t)
        persist(); refreshLook()
        if (trigger == null) ap.saveTargets(ap.targets() + t)
        return id
    }

    fun removeTarget(id: Int) {
        val tv = views.firstOrNull { it.t.id == id } ?: run {
            ap.saveTargets(ap.targets().filter { it.id != id }); return
        }
        runCatching { wm.removeView(tv.view) }
        views.remove(tv)
        persist()
    }

    fun setTargetEnabled(id: Int, on: Boolean) {
        views.firstOrNull { it.t.id == id }?.t?.enabled = on
        persist(); refreshLook()
    }

    fun setEditing(on: Boolean) {
        if (isRunning) return
        editing = on
        refreshLook()
    }

    val isEditing: Boolean get() = editing

    fun targetsSnapshot(): List<ClickTarget> =
        if (views.isNotEmpty()) views.map { it.t.copy() } else ap.targets()

    fun hide() {
        stop()
        holdJob?.cancel(); holdJob = null
        views.forEach { runCatching { wm.removeView(it.view) } }
        views.clear()
        trigger?.let { runCatching { wm.removeView(it) } }
        trigger = null; trigParams = null
        editing = false
    }

    // ---------- เริ่ม/หยุด ----------
    fun toggle(scope: CoroutineScope): Boolean {
        if (isRunning) stop() else start(scope)
        return isRunning
    }

    fun start(scope: CoroutineScope) {
        if (isRunning) return
        if (views.none { it.t.enabled } && ap.targets().none { it.enabled }) return
        isRunning = true
        editing = false
        refreshLook()
        clickJob = scope.launch(Dispatchers.Default) {
            val half = dp(48) / 2f
            val t0 = SystemClock.uptimeMillis()
            while (isActive && SystemClock.uptimeMillis() - t0 < MAX_RUN_MS) {
                // อ่านตำแหน่งสดทุกรอบ (ผู้ใช้อาจเพิ่ม/ลบ/ย้ายเป้า)
                val pts = if (views.isNotEmpty()) views.filter { it.t.enabled }.map { it.lp.x + half to it.lp.y + half }
                else ap.targets().filter { it.enabled }.map { it.x + half to it.y + half }
                if (pts.isEmpty()) break
                val s = SystemClock.uptimeMillis()
                when (ap.clickMode) {
                    1 -> holdChain(pts)
                    2 -> {
                        awaitTap(pts, ap.tapDurationMs.toLong())
                        delay(60L)                                     // ระยะห่างสั้นๆ ระหว่าง 2 แตะ
                        awaitTap(pts, ap.tapDurationMs.toLong())
                        delay((ap.clickIntervalMs - (SystemClock.uptimeMillis() - s)).coerceAtLeast(5L))
                    }
                    else -> {
                        awaitTap(pts, ap.tapDurationMs.toLong())
                        delay((ap.clickIntervalMs - (SystemClock.uptimeMillis() - s)).coerceAtLeast(5L))
                    }
                }
            }
            withContext(Dispatchers.Main) { stop() }
        }
    }

    fun stop() {
        val was = isRunning
        isRunning = false
        clickJob?.cancel(); clickJob = null
        if (was || trigger != null) refreshLook()
    }

    // ---------- Gesture ----------
    private fun pathAt(x: Float, y: Float) = Path().apply { moveTo(x, y); lineTo(x, y) }

    /** กดค้างต่อเนื่องทุกเป้าพร้อมกัน ต่อ stroke ด้วย willContinue ไม่ให้มีช่วงปล่อยนิ้ว */
    private suspend fun holdChain(pts: List<Pair<Float, Float>>) {
        if (Build.VERSION.SDK_INT < 26) return
        val chunk = 2000L
        var prev: List<GestureDescription.StrokeDescription>? = null
        repeat(5) { i ->
            val strokes = pts.mapIndexed { idx, (x, y) ->
                val path = pathAt(x, y)
                prev?.get(idx)?.continueStroke(path, 0, chunk, i < 4)
                    ?: GestureDescription.StrokeDescription(path, 0, chunk, true)
            }
            prev = strokes
            val b = GestureDescription.Builder()
            strokes.forEach { b.addStroke(it) }
            if (!awaitGesture(b.build(), chunk + 400)) return
        }
    }

    private suspend fun awaitGesture(desc: GestureDescription, timeoutMs: Long): Boolean {
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Unit> { cont ->
                val ok = service.dispatchGesture(desc, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) { completed.set(true); if (cont.isActive) cont.resume(Unit) {} }
                    override fun onCancelled(g: GestureDescription?) { if (cont.isActive) cont.resume(Unit) {} }
                }, null)
                if (!ok && cont.isActive) cont.resume(Unit) {}
            }
        }
        return completed.get()
    }

    /** แตะทุกเป้าพร้อมกัน ค้างตาม Tap Duration แล้วรอผลจากระบบ */
    private suspend fun awaitTap(pts: List<Pair<Float, Float>>, durationMs: Long) {
        if (Build.VERSION.SDK_INT < 24) return
        val d = durationMs.coerceIn(1L, 2000L)
        val b = GestureDescription.Builder()
        // ระบบจำกัดจำนวน stroke ต่อท่า (GestureDescription.getMaxStrokeCount)
        pts.take(GestureDescription.getMaxStrokeCount()).forEach { (x, y) ->
            b.addStroke(GestureDescription.StrokeDescription(pathAt(x, y), 0, d))
        }
        awaitGesture(b.build(), d + 300)
    }
}

// ============================ CONTROL BAR + SETTINGS WINDOW ============================
/**
 * แถบควบคุมลอย: ▶ เริ่ม/หยุด, ⚙ ตั้งค่า, ＋ เพิ่ม Target, 🔓/🔒 จัดวาง, ✕ ปิด
 * หน้าต่าง ⚙ ตั้งค่าเป็นหน้าต่างลอยแยก (ไม่แย่งโฟกัสจากเกม) ใช้ได้ทั้งในเกมและแอปอื่น
 */
class AutoClickPanel(
    private val ctx: Context,
    private val wm: WindowManager,
    private val ap: AssistPrefs,
    private val clicker: AutoClickerTarget,
    private val scope: CoroutineScope,
) {
    private val unit = DisplayMetrics.DENSITY_DEVICE_STABLE / 160f
    private fun dp(v: Int) = (v * unit).roundToInt()
    private var bar: View? = null
    private var settings: View? = null
    private var goBtn: TextView? = null
    private var editBtn: TextView? = null

    val shown: Boolean get() = bar?.isAttachedToWindow == true

    private fun chip(text: String, color: Int, onClick: (TextView) -> Unit) = TextView(ctx).apply {
        this.text = text
        setTextColor(0xFFFFFFFF.toInt())
        typeface = Typeface.DEFAULT_BOLD
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 12f * unit)
        gravity = Gravity.CENTER
        setPadding(dp(11), dp(8), dp(11), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(color) }
        setOnClickListener { onClick(this) }
    }

    private fun fill(v: TextView, color: Int) { (v.background as GradientDrawable).setColor(color) }

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (shown) return
        bar?.let { runCatching { wm.removeView(it) } }
        clicker.show()

        val go = chip(if (clicker.isRunning) "■" else "▶", 0xFF00A152.toInt()) { v ->
            val run = clicker.toggle(scope)
            v.text = if (run) "■" else "▶"
            fill(v, if (run) 0xFFD50000.toInt() else 0xFF00A152.toInt())
        }
        val gear = chip("⚙", 0xFF4527A0.toInt()) { toggleSettings() }
        val add = chip("＋", 0xFF0277BD.toInt()) { clicker.addTarget() }
        val edit = chip("🔒", 0xFF455A64.toInt()) { v ->
            clicker.setEditing(!clicker.isEditing)
            v.text = if (clicker.isEditing) "🔓" else "🔒"
            fill(v, if (clicker.isEditing) 0xFF00838F.toInt() else 0xFF455A64.toInt())
        }
        val close = chip("✕", 0xFF37474F.toInt()) { hide() }
        goBtn = go; editBtn = edit

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(20).toFloat(); setColor(0xE6101020.toInt()) }
            listOf(go, gear, add, edit, close).forEachIndexed { i, v ->
                if (i > 0) addView(Space(ctx).apply { minimumWidth = dp(5) })
                addView(v)
            }
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = ap.panelX; y = ap.panelY
            if (Build.VERSION.SDK_INT >= 28)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        row.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = p.x; sy = p.y; tx = e.rawX; ty = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - tx) > dp(6) || abs(e.rawY - ty) > dp(6)) moved = true
                    if (moved) { p.x = sx + (e.rawX - tx).toInt(); p.y = sy + (e.rawY - ty).toInt(); runCatching { wm.updateViewLayout(row, p) } }
                }
                MotionEvent.ACTION_UP -> if (moved) { ap.panelX = p.x; ap.panelY = p.y }
            }
            true
        }
        try {
            wm.addView(row, p)
            bar = row
            GameDetectorService.lastDecision.value = "แผง Auto-Click ขึ้นแล้ว (${clicker.targetCount} เป้า)"
        } catch (e: Throwable) {
            GameDetectorService.lastDecision.value = "สร้างแผง Auto-Click ไม่สำเร็จ: ${e.javaClass.simpleName} ${e.message?.take(80)}"
            clicker.hide()
        }
    }

    fun hide() {
        hideSettings()
        clicker.hide()
        bar?.let { runCatching { wm.removeView(it) } }
        bar = null; goBtn = null; editBtn = null
    }

    fun toggleShown() { if (shown) hide() else show() }

    // ---------- หน้าต่างตั้งค่า ----------
    fun toggleSettings() { if (settings != null) hideSettings() else showSettings() }

    fun hideSettings() {
        settings?.let { runCatching { wm.removeView(it) } }
        settings = null
    }

    private fun label(s: String, size: Float = 12f, color: Int = 0xFFFFFFFF.toInt(), bold: Boolean = false) = TextView(ctx).apply {
        text = s; setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * unit)
        typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    /** แถวปรับค่าด้วยปุ่ม − / ＋ (ใช้ได้ในหน้าต่างที่ไม่รับโฟกัส ไม่ต้องใช้คีย์บอร์ด) */
    private fun stepper(title: String, get: () -> Int, set: (Int) -> Unit, min: Int, max: Int, step: Int, unitTxt: String): View {
        val value = label("", 12f, 0xFFFFD600.toInt(), true).apply { gravity = Gravity.CENTER }
        fun refresh() { value.text = "${get()}$unitTxt" }
        fun bump(d: Int) { set((get() + d).coerceIn(min, max)); refresh() }
        refresh()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(title, 12f).apply { minWidth = dp(112) })
            addView(chip("−", 0xFF455A64.toInt()) { bump(-step) })
            addView(value, LinearLayout.LayoutParams(dp(74), LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(chip("＋", 0xFF455A64.toInt()) { bump(step) })
        }
    }

    private fun switchRow(title: String, sub: String, get: () -> Boolean, set: (Boolean) -> Unit): View {
        val btn = chip(if (get()) "เปิด" else "ปิด", if (get()) 0xFF00A152.toInt() else 0xFF455A64.toInt()) { }
        btn.setOnClickListener {
            set(!get())
            btn.text = if (get()) "เปิด" else "ปิด"
            fill(btn, if (get()) 0xFF00A152.toInt() else 0xFF455A64.toInt())
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(title, 12f, bold = true))
                addView(label(sub, 10f, 0xFF90A4AE.toInt()))
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(btn)
        }
    }

    private fun section(s: String) = label(s, 12f, 0xFF00E5FF.toInt(), true).apply { setPadding(0, dp(10), 0, dp(2)) }

    @SuppressLint("ClickableViewAccessibility")
    private fun showSettings() {
        if (settings != null) return
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
        }
        fun gap() = Space(ctx).apply { minimumHeight = dp(6) }

        box.addView(label("AUTO-CLICK SETTINGS", 13f, 0xFFFFD600.toInt(), true))

        // ---- Action Modes ----
        box.addView(section("รูปแบบการกด"))
        val modes = listOf("Single Tap" to 0, "Double Tap" to 2, "Hold" to 1)
        val modeChips = mutableListOf<Pair<TextView, Int>>()
        val modeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun paintModes() = modeChips.forEach { (c, m) -> fill(c, if (ap.clickMode == m) 0xFF00A152.toInt() else 0xFF455A64.toInt()) }
        modes.forEachIndexed { i, (name, m) ->
            val c = chip(name, 0xFF455A64.toInt()) { ap.clickMode = m; paintModes() }
            modeChips += c to m
            if (i > 0) modeRow.addView(Space(ctx).apply { minimumWidth = dp(5) })
            modeRow.addView(c)
        }
        paintModes()
        box.addView(modeRow)

        // ---- Timing ----
        box.addView(section("เวลา (Timing)"))
        box.addView(stepper("Tap Interval", { ap.clickIntervalMs }, { ap.clickIntervalMs = it }, 10, 2000, 10, " ms"))
        box.addView(gap())
        box.addView(stepper("Tap Duration", { ap.tapDurationMs }, { ap.tapDurationMs = it }, 1, 500, 5, " ms"))

        // ---- Triggers ----
        box.addView(section("ทริกเกอร์ (Triggers)"))
        box.addView(switchRow("แตะสลับเริ่ม/หยุด", "ปิด = กดค้างที่ปุ่ม FIRE เพื่อยิง ปล่อยแล้วหยุด",
            { ap.triggerToggle }, { ap.triggerToggle = it; clicker.refreshLook() }))
        box.addView(gap())
        box.addView(stepper("Triggers Sensitivity", { ap.triggerSize }, {
            ap.triggerSize = it; clicker.rebuildTrigger()
        }, 36, 120, 4, " dp"))
        box.addView(label("ขนาดพื้นที่รับทริกเกอร์: ใหญ่ = แตะติดง่ายขึ้น", 10f, 0xFF90A4AE.toInt()))
        box.addView(gap())
        box.addView(stepper("Trigger Delay", { ap.triggerHoldMs }, { ap.triggerHoldMs = it }, 0, 1500, 50, " ms"))
        box.addView(label("กดค้างนานเท่านี้ก่อนเริ่มยิง (0 = ทันที)", 10f, 0xFF90A4AE.toInt()))
        box.addView(gap())
        box.addView(switchRow("Hide Sensitivity Triggers", "ซ่อนกรอบทริกเกอร์/เป้า ยังทำงานตามปกติ",
            { ap.triggerHidden }, { ap.triggerHidden = it; clicker.refreshLook() }))

        // ---- Accidental touch ----
        box.addView(section("กันแตะพลาด (Accidental Touch)"))
        box.addView(switchRow("Accidental Touch Protection", "ต้องกดค้างอย่างน้อย 250 ms จึงทำงาน และเป้าถูกล็อกจนกว่าจะกด 🔓",
            { ap.accidentalGuard }, { ap.accidentalGuard = it; clicker.refreshLook() }))

        // ---- Targets ----
        box.addView(section("เป้าหมาย (Targets)"))
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        fun rebuildList() {
            list.removeAllViews()
            clicker.targetsSnapshot().forEach { t ->
                list.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(3), 0, dp(3))
                    addView(label("Target ${t.id}", 12f, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    val en = chip(if (t.enabled) "เปิด" else "ปิด", if (t.enabled) 0xFF00A152.toInt() else 0xFF455A64.toInt()) { }
                    en.setOnClickListener {
                        val on = !(clicker.targetsSnapshot().firstOrNull { it.id == t.id }?.enabled ?: true)
                        clicker.setTargetEnabled(t.id, on); rebuildList()
                    }
                    addView(en)
                    addView(Space(ctx).apply { minimumWidth = dp(5) })
                    addView(chip("ลบ", 0xFFD50000.toInt()) {
                        // เก็บอย่างน้อย 1 เป้าเสมอ ไม่งั้นไม่มีอะไรให้กด
                        if (clicker.targetsSnapshot().size > 1) { clicker.removeTarget(t.id); rebuildList() }
                    })
                })
            }
        }
        rebuildList()
        box.addView(list)
        box.addView(chip("＋ เพิ่ม Target", 0xFF0277BD.toInt()) { clicker.addTarget(); rebuildList() }.apply { setPadding(dp(12), dp(9), dp(12), dp(9)) })
        box.addView(label("กด 🔓 บนแถบควบคุมเพื่อลากเป้า/ทริกเกอร์ไปวาง แล้วกด 🔒 เพื่อล็อก", 10f, 0xFF90A4AE.toInt()))
        box.addView(gap())
        box.addView(chip("ปิดหน้าต่างตั้งค่า", 0xFF37474F.toInt()) { hideSettings() })

        val sv = ScrollView(ctx).apply {
            addView(box)
            background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0xF0101020.toInt()) }
        }
        val dm = ctx.resources.displayMetrics
        val w = minOf(dm.widthPixels, dm.heightPixels).let { (it * 0.82f).toInt().coerceAtLeast(dp(260)) }
        val h = (dm.heightPixels * 0.78f).toInt()
        val p = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }
        try {
            wm.addView(sv, p)
            settings = sv
        } catch (e: Throwable) {
            GameDetectorService.lastDecision.value = "เปิดหน้าตั้งค่า Auto-Click ไม่สำเร็จ: ${e.javaClass.simpleName}"
        }
    }
}
