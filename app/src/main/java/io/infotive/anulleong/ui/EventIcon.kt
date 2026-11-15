package io.infotive.anulleong.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.infotive.anulleong.care.AheadEvent
import io.infotive.anulleong.care.EventKind

/**
 * Small vector pictograms for road sections, drawn the same on every device
 * (unicode arrows and emoji rendered differently per font).
 */
@Composable
fun EventIcon(e: AheadEvent, color: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawEventIcon(e, color, center, this.size.minDimension / 2f) }
}

fun DrawScope.drawEventIcon(e: AheadEvent, color: Color, c: Offset, r: Float) {
    val w = r * 0.22f
    val st = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val left = e.title.startsWith("왼")
    fun arrowHead(tip: Offset, dir: Float) { // dir in degrees, 0 = right
        val a = Math.toRadians(dir.toDouble())
        val back = 0.42f * r
        val p = Path()
        for (s in listOf(150.0, -150.0)) {
            val b = a + Math.toRadians(s)
            p.moveTo(tip.x, tip.y); p.lineTo(tip.x + (back * Math.cos(b)).toFloat(), tip.y + (back * Math.sin(b)).toFloat())
        }
        drawPath(p, color, style = st)
    }
    when (e.kind) {
        EventKind.TURN, EventKind.MERGE -> {
            val sx = if (left) 1f else -1f
            val p = Path().apply {
                moveTo(c.x + 0.25f * r * sx, c.y + 0.75f * r)
                lineTo(c.x + 0.25f * r * sx, c.y - 0.05f * r)
                quadraticBezierTo(c.x + 0.25f * r * sx, c.y - 0.4f * r, c.x - 0.1f * r * sx, c.y - 0.4f * r)
                lineTo(c.x - 0.6f * r * sx, c.y - 0.4f * r)
            }
            drawPath(p, color, style = st)
            arrowHead(Offset(c.x - 0.6f * r * sx, c.y - 0.4f * r), if (left) 180f else 0f)
        }
        EventKind.SHARP_TURN -> {
            drawArc(color, 200f, 290f, false, Offset(c.x - 0.55f * r, c.y - 0.55f * r), Size(1.1f * r, 1.1f * r), style = st)
            arrowHead(Offset(c.x - 0.52f * r, c.y - 0.18f * r), 110f)
        }
        EventKind.RAMP -> {
            val p = Path().apply {
                moveTo(c.x - 0.5f * r, c.y + 0.7f * r)
                quadraticBezierTo(c.x - 0.45f * r, c.y - 0.1f * r, c.x + 0.5f * r, c.y - 0.5f * r)
            }
            drawPath(p, color, style = st)
            arrowHead(Offset(c.x + 0.5f * r, c.y - 0.5f * r), -25f)
        }
        EventKind.BUMP -> {
            drawLine(color, Offset(c.x - 0.75f * r, c.y + 0.35f * r), Offset(c.x + 0.75f * r, c.y + 0.35f * r), w, StrokeCap.Round)
            drawArc(color, 180f, 180f, false, Offset(c.x - 0.45f * r, c.y - 0.15f * r), Size(0.9f * r, 1.0f * r), style = st)
        }
        EventKind.DOWNHILL -> {
            drawLine(color, Offset(c.x - 0.7f * r, c.y - 0.45f * r), Offset(c.x + 0.7f * r, c.y + 0.45f * r), w, StrokeCap.Round)
            drawLine(color, Offset(c.x - 0.7f * r, c.y + 0.45f * r), Offset(c.x + 0.7f * r, c.y + 0.45f * r), w * 0.7f, StrokeCap.Round)
        }
        EventKind.ROUGH_ROAD -> for (i in -1..1) {
            val y = c.y + i * 0.38f * r
            val p = Path().apply {
                moveTo(c.x - 0.7f * r, y)
                quadraticBezierTo(c.x - 0.35f * r, y - 0.25f * r, c.x, y)
                quadraticBezierTo(c.x + 0.35f * r, y + 0.25f * r, c.x + 0.7f * r, y)
            }
            drawPath(p, color, style = Stroke(w * 0.8f, cap = StrokeCap.Round))
        }
        EventKind.NARROW -> {
            drawLine(color, Offset(c.x - 0.6f * r, c.y - 0.7f * r), Offset(c.x - 0.2f * r, c.y + 0.7f * r), w, StrokeCap.Round)
            drawLine(color, Offset(c.x + 0.6f * r, c.y - 0.7f * r), Offset(c.x + 0.2f * r, c.y + 0.7f * r), w, StrokeCap.Round)
        }
        EventKind.SLOWDOWN -> {
            drawLine(color, Offset(c.x, c.y - 0.7f * r), Offset(c.x, c.y + 0.6f * r), w, StrokeCap.Round)
            arrowHead(Offset(c.x, c.y + 0.6f * r), 90f)
        }
        EventKind.REST -> {
            drawRoundRect(color, Offset(c.x - 0.5f * r, c.y - 0.3f * r), Size(0.8f * r, 0.8f * r),
                androidx.compose.ui.geometry.CornerRadius(0.15f * r), style = st)
            drawArc(color, -90f, 180f, false, Offset(c.x + 0.12f * r, c.y - 0.15f * r), Size(0.36f * r, 0.4f * r), style = st)
        }
        EventKind.ARRIVE -> {
            drawLine(color, Offset(c.x - 0.45f * r, c.y - 0.75f * r), Offset(c.x - 0.45f * r, c.y + 0.75f * r), w, StrokeCap.Round)
            drawPath(Path().apply {
                moveTo(c.x - 0.45f * r, c.y - 0.7f * r); lineTo(c.x + 0.6f * r, c.y - 0.45f * r); lineTo(c.x - 0.45f * r, c.y - 0.1f * r); close()
            }, color)
        }
        EventKind.PAST_SICK -> {
            val p = Path().apply {
                moveTo(c.x, c.y + 0.8f * r)
                cubicTo(c.x - 0.9f * r, c.y - 0.05f * r, c.x - 0.6f * r, c.y - 0.85f * r, c.x, c.y - 0.85f * r)
                cubicTo(c.x + 0.6f * r, c.y - 0.85f * r, c.x + 0.9f * r, c.y - 0.05f * r, c.x, c.y + 0.8f * r)
                close()
            }
            drawPath(p, color)
            drawCircle(Color.White, 0.22f * r, Offset(c.x, c.y - 0.28f * r))
        }
    }
}

@Suppress("unused")
private fun Rect.unused() = Unit
