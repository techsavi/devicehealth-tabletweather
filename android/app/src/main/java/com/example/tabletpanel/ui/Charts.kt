package com.example.tabletpanel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tabletpanel.weather.Fmt
import com.example.tabletpanel.weather.HourPoint
import com.example.tabletpanel.weather.MinutePoint
import java.time.ZoneId
import java.time.format.TextStyle as DayStyle
import java.util.Locale
import kotlin.math.max

private val LabelStyle = TextStyle(color = PanelColors.TextDim, fontSize = 16.sp)
private val ValueStyle = TextStyle(color = PanelColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Medium)

/** x-centre of slot [i] when [n] slots share the width evenly — matches a Row of n weight(1f) cells. */
private fun DrawScope.slotX(i: Int, n: Int) = (i + 0.5f) * size.width / n

private fun DrawScope.label(tm: TextMeasurer, text: String, x: Float, y: Float, style: TextStyle = LabelStyle, above: Boolean = false) {
    val l = tm.measure(text, style)
    val left = (x - l.size.width / 2f).coerceIn(0f, max(0f, size.width - l.size.width))
    drawText(l, topLeft = Offset(left, if (above) y - l.size.height else y))
}

/** Row of [n] equal cells with [content] drawn in every [step]th one, allowed to overflow its cell. */
@Composable
fun SlotRow(n: Int, step: Int, modifier: Modifier = Modifier, content: @Composable (Int) -> Unit) {
    Row(modifier.fillMaxWidth()) {
        for (i in 0 until n) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (i % step == 0) Box(Modifier.wrapContentWidth(unbounded = true)) { content(i) }
            }
        }
    }
}

/** Smoothed temperature line with values every [labelEvery] hours and a marker at midnight. */
@Composable
fun TemperatureLine(hours: List<HourPoint>, fmt: Fmt, modifier: Modifier, labelEvery: Int = 2) {
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        if (hours.size < 2) return@Canvas
        val temps = hours.map { fmt.tempValue(it.temp).toFloat() }
        val lo = temps.min()
        val hi = max(temps.max(), lo + 6f)
        val top = 44.dp.toPx()
        val bottom = size.height - 14.dp.toPx()
        fun y(t: Float) = bottom - (t - lo) / (hi - lo) * (bottom - top)
        val pts = temps.mapIndexed { i, t -> Offset(slotX(i, hours.size), y(t)) }

        midnights(hours).forEach { i ->
            val x = slotX(i, hours.size) - size.width / hours.size / 2
            drawLine(PanelColors.Divider, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
            val day = hours[i].time.atZone(ZoneId.systemDefault()).dayOfWeek.getDisplayName(DayStyle.SHORT, Locale.getDefault())
            drawText(tm.measure(day, LabelStyle), topLeft = Offset(x + 8.dp.toPx(), 0f))
        }

        val line = smoothPath(pts)
        val fill = Path().apply {
            addPath(line)
            lineTo(pts.last().x, size.height)
            lineTo(pts.first().x, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(PanelColors.Warm.copy(alpha = 0.28f), Color.Transparent), startY = top, endY = size.height))
        drawPath(line, PanelColors.Warm, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))

        pts.forEachIndexed { i, p ->
            if (i % labelEvery == 0) {
                drawCircle(PanelColors.Warm, 4.dp.toPx(), p)
                label(tm, "${temps[i].toInt()}°", p.x, p.y - 10.dp.toPx(), ValueStyle, above = true)
            }
        }
    }
}

/** Precipitation probability bars (light) with the expected amount overlaid (solid). */
@Composable
fun PrecipBars(hours: List<HourPoint>, fmt: Fmt, modifier: Modifier, showAmounts: Boolean = false) {
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        val n = hours.size.coerceAtLeast(1)
        val slot = size.width / n
        val labelSpace = 24.dp.toPx()
        val chartH = size.height - labelSpace
        val maxAmount = max(hours.maxOfOrNull { it.precip } ?: 0.0, 2.5)
        drawLine(PanelColors.Divider, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 2f)
        hours.forEachIndexed { i, h ->
            val x = i * slot + slot * 0.18f
            val w = slot * 0.64f
            val ph = chartH * h.precipProb / 100f
            if (ph > 0) drawRoundRect(PanelColors.RainLight, Offset(x, size.height - ph), Size(w, ph), CornerRadius(4.dp.toPx()))
            val ah = (chartH * (h.precip / maxAmount)).toFloat()
            if (ah > 1f) drawRoundRect(PanelColors.Rain, Offset(x, size.height - ah), Size(w, ah), CornerRadius(4f))
            val text = when {
                // Label only local peaks so neighbouring bars' numbers don't collide.
                showAmounts && h.precip >= 0.25 && h.precip >= (hours.getOrNull(i - 1)?.precip ?: 0.0) &&
                    h.precip > (hours.getOrNull(i + 1)?.precip ?: 0.0) -> fmt.precip(h.precip).substringBefore(' ')
                !showAmounts && h.precipProb >= 20 && i % 2 == 0 -> "${h.precipProb}%"
                else -> null
            }
            if (text != null) label(tm, text, x + w / 2, size.height - max(ph, ah) - 4.dp.toPx(), LabelStyle.copy(color = PanelColors.Rain), above = true)
        }
    }
}

/** Rain rate over the next hours in 15-minute steps; empty state when it stays dry. */
@Composable
fun NowcastBars(slots: List<MinutePoint>, fmt: Fmt, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        val labelH = 26.dp.toPx()
        val chartH = size.height - labelH
        val baseline = chartH
        drawLine(PanelColors.Divider, Offset(0f, baseline), Offset(size.width, baseline), 1.dp.toPx())
        if (slots.isEmpty()) return@Canvas
        val n = slots.size
        val slot = size.width / n
        // mm per 15 min -> mm/h; scale so light rain is visible but heavy rain doesn't clip.
        val rates = slots.map { it.precip * 4 }
        val maxRate = max(rates.max(), 4.0)
        slots.forEachIndexed { i, s ->
            val rate = rates[i]
            if (rate > 0.05) {
                val h = (chartH * (rate / maxRate)).toFloat().coerceAtLeast(4.dp.toPx())
                val color = when {
                    rate >= 7.6 -> PanelColors.Storm
                    rate >= 2.5 -> PanelColors.Rain
                    else -> PanelColors.RainLight
                }
                drawRoundRect(color, Offset(i * slot + slot * 0.12f, baseline - h), Size(slot * 0.76f, h), CornerRadius(3f))
            }
            // "Now" under the first bar, then each whole hour at the bar boundary where it falls.
            if (i == 0) label(tm, "Now", slot / 2, baseline + 4.dp.toPx())
            if (i >= 3 && s.time.epochSecond % 3600 == 0L) label(tm, fmt.hour(s.time), (i + 1) * slot, baseline + 4.dp.toPx())
        }
        if (rates.all { it <= 0.05 }) {
            label(tm, "No rain in the next ${n / 4} hours", size.width / 2, chartH / 2 - 14.dp.toPx(), ValueStyle.copy(color = PanelColors.TextSecondary))
        }
    }
}

/** Sustained wind (solid) and gusts (dashed) for the coming hours. */
@Composable
fun WindChart(hours: List<HourPoint>, fmt: Fmt, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    Canvas(modifier) {
        if (hours.size < 2) return@Canvas
        val wind = hours.map { fmt.speedValue(it.wind).toFloat() }
        val gust = hours.map { fmt.speedValue(it.gust).toFloat() }
        val top = 40.dp.toPx()
        val bottom = size.height - 28.dp.toPx()
        val hi = max(gust.max(), if (fmt.metric) 30f else 20f) * 1.1f
        fun pt(i: Int, v: Float) = Offset(slotX(i, hours.size), bottom - v / hi * (bottom - top))

        val windPts = wind.indices.map { pt(it, wind[it]) }
        val gustPts = gust.indices.map { pt(it, gust[it]) }
        drawPath(
            Path().apply {
                addPath(smoothPath(windPts))
                lineTo(windPts.last().x, bottom)
                lineTo(windPts.first().x, bottom)
                close()
            },
            Brush.verticalGradient(listOf(PanelColors.Accent.copy(alpha = 0.3f), Color.Transparent), startY = top, endY = bottom),
        )
        drawPath(smoothPath(gustPts), PanelColors.Warm, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12.dp.toPx(), 10.dp.toPx()))))
        drawPath(smoothPath(windPts), PanelColors.Accent, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        drawLine(PanelColors.Divider, Offset(0f, bottom), Offset(size.width, bottom), 1.dp.toPx())

        val peak = gust.indices.maxBy { gust[it] }
        label(tm, "gusts ${gust[peak].toInt()}", gustPts[peak].x, gustPts[peak].y - 6.dp.toPx(), ValueStyle.copy(color = PanelColors.Warm), above = true)
        hours.indices.forEach { i ->
            if (i % 3 == 0) {
                label(tm, fmt.hour(hours[i].time), slotX(i, hours.size), bottom + 4.dp.toPx())
                if (i != peak) label(tm, "${wind[i].toInt()}", windPts[i].x, windPts[i].y - 6.dp.toPx(), LabelStyle.copy(color = PanelColors.Accent), above = true)
            }
        }
    }
}

/** Indices of hours that start a new calendar day. */
private fun midnights(hours: List<HourPoint>): List<Int> {
    val zone = ZoneId.systemDefault()
    return (1 until hours.size).filter {
        hours[it].time.atZone(zone).toLocalDate() != hours[it - 1].time.atZone(zone).toLocalDate()
    }
}

/** Catmull-Rom style smoothing through the points, gentle enough not to overshoot much. */
private fun smoothPath(pts: List<Offset>): Path = Path().apply {
    moveTo(pts.first().x, pts.first().y)
    for (i in 0 until pts.size - 1) {
        val p0 = pts[maxOf(i - 1, 0)]
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = pts[minOf(i + 2, pts.size - 1)]
        val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
        val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
        cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
    }
}

