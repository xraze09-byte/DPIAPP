package com.dpi.gamingtweaker

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** การ์ดพื้นเข้มมีหัวข้อ สี accent และคำอธิบายใต้หัวข้อ */
@Composable
internal fun GlassCard(
    title: String,
    accent: Color = NeonCyan,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(TileDark)
            .border(1.dp, accent.copy(alpha = .45f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = accent, fontWeight = FontWeight.Black, fontSize = 14.sp)
        if (subtitle != null) Text(subtitle, color = Color.Gray, fontSize = 11.sp)
        content()
    }
}

/** สไลเดอร์จำนวนเต็ม: step = ขนาดก้าว, offAtZero = แสดง "ปิด" เมื่อค่าเป็น 0 */
@Composable
internal fun IntSlider(
    label: String, value: Int, range: IntRange, step: Int, suffix: String,
    offAtZero: Boolean = false, enabled: Boolean = true, onChange: (Int) -> Unit
) {
    val shown = if (offAtZero && value == 0) "ปิด" else "$value$suffix"
    val steps = ((range.last - range.first) / step.coerceAtLeast(1) - 1).coerceAtLeast(0)
    Column {
        Text("$label: $shown", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Slider(
            value = value.toFloat(),
            onValueChange = {
                val snapped = (it / step).roundToInt() * step
                onChange(snapped.coerceIn(range.first, range.last))
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            enabled = enabled,
            steps = steps
        )
    }
}
