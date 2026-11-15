package io.infotive.anulleong.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.infotive.anulleong.care.AheadEvent
import io.infotive.anulleong.care.Severity

private const val RANGE_M = 1500f

/**
 * The app's one memorable element: a winding road drawn from the car's point of view.
 * Upcoming turns and bumps sit on it as stickers at their distance, so a child can
 * literally look up and "see" what is coming. Lane dashes flow only while a route is live.
 */
@Composable
fun RoadHero(
    events: List<AheadEvent>,
    moving: Boolean,
    highlightKey: String?,
    headline: String,
    sub: String?,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val flow = rememberInfiniteTransition(label = "road")
    val phase by flow.animateFloat(0f, if (moving) 60f else 0f,
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "dash")
    val pulse by flow.animateFloat(1f, 1.18f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "pulse")
    val placed = events.filter { it.distanceM != null }.take(6)

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.verticalGradient(listOf(C.skyDeep, C.sky)))
            val w = size.width; val h = size.height
            val roadW = 58.dp.toPx()
            // Road lives in the right part of the hero; the headline takes the left.
            val cx = w * 0.72f
            val path = android.graphics.Path().apply {
                moveTo(cx, h + roadW)
                cubicTo(cx - w * 0.22f, h * 0.72f, cx + w * 0.20f, h * 0.48f, cx - w * 0.04f, h * 0.30f)
                cubicTo(cx - w * 0.20f, h * 0.16f, cx + w * 0.10f, h * 0.02f, cx + w * 0.02f, -roadW)
            }
            val cp = path.asComposePath()
            drawPath(cp, C.grass, style = Stroke(roadW * 1.9f, cap = StrokeCap.Round))
            drawPath(cp, C.road, style = Stroke(roadW, cap = StrokeCap.Round))
            drawPath(cp, Color.White.copy(alpha = .85f), style = Stroke(3.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(18.dp.toPx(), 14.dp.toPx()), -phase.dp.toPx())))

            val pm = android.graphics.PathMeasure(path, false)
            val len = pm.length
            val pos = FloatArray(2); val tan = FloatArray(2)
            fun at(fraction: Float): Pair<Offset, Float> {
                pm.getPosTan(len * fraction, pos, tan)
                return Offset(pos[0], pos[1]) to Math.toDegrees(kotlin.math.atan2(tan[1], tan[0]).toDouble()).toFloat()
            }
            // The path starts below the hero; park the car where it is fully visible.
            var carF = 0f
            while (carF < 0.4f) { pm.getPosTan(len * carF, pos, tan); if (pos[1] <= h - 40.dp.toPx()) break; carF += 0.005f }
            val startF = carF + 0.09f
            // Stickers far → near so the next one is drawn on top.
            placed.asReversed().forEach { e ->
                val f = startF + (0.97f - startF) * (e.distanceM!!.coerceIn(0, RANGE_M.toInt()) / RANGE_M)
                val (p, _) = at(f)
                val isNext = e === placed.first()
                val r = (if (isNext) 25.dp.toPx() else 18.dp.toPx()) * (if (e.key == highlightKey) pulse else 1f)
                sticker(p, r, eventColor(e), glyph(e), measurer, if (isNext) 27.sp.value else 19.sp.value)
            }
            val (carP, carA) = at(carF)
            car(carP, carA + 90f)
        }
        Column(Modifier.fillMaxHeight().fillMaxWidth(0.6f).padding(start = 20.dp, top = 18.dp, bottom = 18.dp)) {
            Text(headline, fontFamily = Jua, fontSize = 30.sp, lineHeight = 37.sp, color = C.ink)
            if (sub != null) {
                Spacer(Modifier.height(10.dp))
                Text(sub, fontSize = 15.sp, lineHeight = 21.sp, color = C.inkSoft, fontWeight = FontWeight.Medium)
            }
        }
    }
}

private fun DrawScope.sticker(c: Offset, r: Float, ring: Color, label: String,
                              measurer: androidx.compose.ui.text.TextMeasurer, fontSp: Float) {
    drawCircle(Color.Black.copy(alpha = .12f), r * 1.04f, c + Offset(0f, r * 0.14f))
    drawCircle(Color.White, r, c)
    drawCircle(ring, r, c, style = Stroke(r * 0.18f))
    val layout = measurer.measure(label, TextStyle(fontSize = fontSp.sp, color = ring, fontWeight = FontWeight.Black))
    drawText(layout, topLeft = Offset(c.x - layout.size.width / 2f, c.y - layout.size.height / 2f))
}

private fun DrawScope.car(c: Offset, angle: Float) {
    val bw = 30.dp.toPx(); val bh = 46.dp.toPx()
    rotate(angle, c) {
        drawRoundRect(Color.Black.copy(alpha = .18f), Offset(c.x - bw / 2, c.y - bh / 2 + 4.dp.toPx()), Size(bw, bh), CornerRadius(12.dp.toPx()))
        drawRoundRect(C.periwinkle, Offset(c.x - bw / 2, c.y - bh / 2), Size(bw, bh), CornerRadius(12.dp.toPx()))
        drawRoundRect(Color.White.copy(alpha = .9f), Offset(c.x - bw / 2 + 5.dp.toPx(), c.y - bh / 2 + 7.dp.toPx()),
            Size(bw - 10.dp.toPx(), 10.dp.toPx()), CornerRadius(4.dp.toPx()))
        drawRoundRect(Color.White.copy(alpha = .55f), Offset(c.x - bw / 2 + 5.dp.toPx(), c.y + bh / 2 - 14.dp.toPx()),
            Size(bw - 10.dp.toPx(), 7.dp.toPx()), CornerRadius(3.dp.toPx()))
    }
}

fun eventColor(e: AheadEvent) = if (e.kind == io.infotive.anulleong.care.EventKind.PAST_SICK) C.lilac else sevColor(e.severity)

fun sevColor(s: Severity) = when (s) { Severity.STRONG -> C.berry; Severity.NOTICE -> C.sun; Severity.GENTLE -> C.periwinkle }
