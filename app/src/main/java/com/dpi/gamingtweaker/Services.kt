package com.dpi.gamingtweaker

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt

// ======================= FLOATING FPS / PING =======================
class OverlayService : Service() {
    companion object {
        /** UI ดึงสถานะจริงจากตรงนี้ สวิตช์จะได้ไม่ค้างเป็น ON */
        val running = MutableStateFlow(false)
        private const val CH = "overlay"
        private const val NID = 42
    }

    private var wm: WindowManager? = null
    private var tv: TextView? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var pingMs: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onCreate() {
        super.onCreate()
        startAsForeground() // ต้องเรียกก่อน stopSelf เสมอ
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        val w = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = w
        try {
            addOverlay(w)
        } catch (e: Exception) {
            stopSelf(); return
        }
        running.value = true
        scope.launch(Dispatchers.IO) { while (isActive) { pingMs = Metrics.tcpPing(); delay(2000) } }
        scope.launch { fpsLoop() }
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "FPS Overlay", NotificationManager.IMPORTANCE_MIN))
        val n = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("FPS/Ping overlay กำลังทำงาน")
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(
            this, NID, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addOverlay(w: WindowManager) {
        val view = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 30f) // px คงที่ ไม่ยืดตาม DPI
            typeface = Typeface.MONOSPACE
            setTextColor(Color.WHITE)
            setBackgroundColor(0xAA000000.toInt())
            setPadding(18, 8, 18, 8)
            text = "FPS -- | -- ms"
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 32; y = 96
            if (Build.VERSION.SDK_INT >= 28)
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        view.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = lp.x; sy = lp.y; tx = e.rawX; ty = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = sx + (e.rawX - tx).toInt(); lp.y = sy + (e.rawY - ty).toInt()
                    runCatching { w.updateViewLayout(v, lp) }; true
                }
                else -> false
            }
        }
        w.addView(view, lp)
        tv = view
    }

    private suspend fun fpsLoop() {
        val display = getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
        var lastCount: Long? = null
        var lastT = 0L
        while (scope.isActive) {
            val count = withContext(Dispatchers.IO) { Metrics.sfFrameCount() }
            val now = SystemClock.elapsedRealtime()
            val fps = if (count != null && lastCount != null && now > lastT)
                ((count - lastCount) * 1000.0 / (now - lastT)).roundToInt().coerceAtLeast(0) else null
            if (count != null) { lastCount = count; lastT = now }

            val fpsText = when {
                fps != null -> "FPS $fps"
                count == null -> "${(display?.refreshRate ?: 0f).roundToInt()}Hz"
                else -> "FPS --"
            }
            tv?.text = "$fpsText | ${pingMs ?: "--"} ms"
            tv?.setTextColor(
                when {
                    fps == null -> Color.WHITE
                    fps >= 85 -> Color.GREEN
                    fps >= 55 -> Color.YELLOW
                    else -> Color.RED
                }
            )
            delay(1000)
        }
    }

    override fun onDestroy() {
        running.value = false
        scope.cancel()
        tv?.let { v -> runCatching { wm?.removeView(v) } }
        tv = null
        super.onDestroy()
    }
}
