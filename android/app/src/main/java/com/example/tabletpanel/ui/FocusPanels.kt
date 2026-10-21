package com.example.tabletpanel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tabletpanel.weather.DayPoint
import com.example.tabletpanel.weather.Fmt
import com.example.tabletpanel.weather.Outlook
import com.example.tabletpanel.weather.PrecipEvent
import com.example.tabletpanel.weather.Weather
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle as DayStyle

// ---------------------------------------------------------------- long-range forecast (fair weather)

@Composable
fun ForecastPanel(w: Weather, fmt: Fmt, now: Instant) {
    val days = w.daily.take(if (LocalLandscape.current) 10 else 7)
    if (days.isEmpty()) return
    val lo = days.minOf { it.tempMin }
    val hi = days.maxOf { it.tempMax }
    val today = now.atZone(ZoneId.systemDefault()).toLocalDate()
    Row(Modifier.fillMaxSize()) {
        days.forEach { d -> DayColumn(d, fmt, today, lo, hi, Modifier.weight(1f)) }
    }
}

@Composable
private fun DayColumn(d: DayPoint, fmt: Fmt, today: LocalDate, weekLo: Double, weekHi: Double, modifier: Modifier) {
    Column(
        modifier
            .fillMaxHeight()
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (d.date == today) PanelColors.Card else Color.Transparent)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(fmt.weekday(d.date, today), color = PanelColors.Text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        WeatherIcon(d.code, true, Modifier.size(64.dp).padding(vertical = 4.dp))
        Text(if (d.precipProb >= 20) "${d.precipProb}%" else " ", color = PanelColors.Rain, fontSize = 19.sp)
        Text(if (d.precipSum >= 1.0) fmt.precip(d.precipSum) else " ", color = PanelColors.TextDim, fontSize = 15.sp, maxLines = 1)
        Text(fmt.temp(d.tempMax), color = PanelColors.Text, fontSize = 28.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
        TempRangeBar(d.tempMin, d.tempMax, weekLo, weekHi, Modifier.weight(1f).width(12.dp).padding(vertical = 6.dp))
        Text(fmt.temp(d.tempMin), color = PanelColors.TextSecondary, fontSize = 24.sp)
    }
}

/** The day's low-high span positioned within the whole period's range. */
@Composable
private fun TempRangeBar(min: Double, max: Double, lo: Double, hi: Double, modifier: Modifier) {
    Canvas(modifier) {
        val r = size.width / 2
        drawRoundRect(PanelColors.Card, cornerRadius = CornerRadius(r))
        val span = (hi - lo).coerceAtLeast(1.0)
        val top = ((hi - max) / span * size.height).toFloat()
        val bottom = ((hi - min) / span * size.height).toFloat().coerceAtLeast(top + size.width)
        drawRoundRect(
            Brush.verticalGradient(listOf(PanelColors.Warm, PanelColors.Cold), startY = top, endY = bottom),
            topLeft = Offset(0f, top),
            size = Size(size.width, bottom - top),
            cornerRadius = CornerRadius(r),
        )
    }
}

// ---------------------------------------------------------------- next 24 hours

@Composable
fun HourlyPanel(w: Weather, fmt: Fmt, now: Instant) {
    val hours = w.hourly.filter { it.time.isAfter(now) }.take(24)
    Column(Modifier.fillMaxSize()) {
        SlotRow(hours.size, 2) { i -> WeatherIcon(hours[i].code, hours[i].isDay, Modifier.size(44.dp)) }
        TemperatureLine(hours, fmt, Modifier.fillMaxWidth().weight(1f))
        PrecipBars(hours, fmt, Modifier.fillMaxWidth().height(70.dp))
        SlotRow(hours.size, 2, Modifier.padding(top = 4.dp)) { i ->
            Text(fmt.hour(hours[i].time), color = PanelColors.TextDim, fontSize = 17.sp, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------- rain & storms

@Composable
fun RainPanel(w: Weather, outlook: Outlook?, fmt: Fmt, now: Instant) {
    val p = outlook?.precip
    Split(
        first = { m -> RainSummary(p, w, fmt, now, m) },
        second = { m ->
            Column(m) {
                ChartLabel("RAIN RATE · NEXT ${w.minutely.size / 4} HOURS")
                NowcastBars(w.minutely.filter { it.time.isAfter(now.minusSeconds(15 * 60)) }, fmt, Modifier.fillMaxWidth().weight(1f).padding(vertical = 6.dp))
                ChartLabel("CHANCE & AMOUNT · NEXT 24 HOURS")
                val hours = w.hourly.filter { it.time.isAfter(now) }.take(24)
                PrecipBars(hours, fmt, Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp), showAmounts = true)
                SlotRow(hours.size, 3, Modifier.padding(top = 4.dp)) { i ->
                    Text(fmt.hour(hours[i].time), color = PanelColors.TextDim, fontSize = 16.sp, maxLines = 1)
                }
            }
        },
    )
}

@Composable
private fun RainSummary(p: PrecipEvent?, w: Weather, fmt: Fmt, now: Instant, modifier: Modifier) {
    val tint = when {
        p == null -> PanelColors.Card
        p.severe -> PanelColors.Storm.copy(alpha = 0.16f)
        else -> PanelColors.Rain.copy(alpha = 0.12f)
    }
    Column(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(tint)
            .padding(horizontal = 26.dp, vertical = 14.dp),
    ) {
        if (p == null) {
            Text("No rain expected", color = PanelColors.Text, fontSize = 44.sp, fontWeight = FontWeight.Medium)
            Text("Dry for the next 24 hours", color = PanelColors.TextSecondary, fontSize = 26.sp)
            val today = now.atZone(ZoneId.systemDefault()).toLocalDate()
            val locale = LocalConfiguration.current.locales[0]
            // Point at the most likely wet day in the extended forecast, or say there isn't one.
            val next = w.daily.filter { it.date.isAfter(today) && it.precipProb >= 20 }.let { days ->
                days.firstOrNull { it.precipProb >= 50 } ?: days.maxByOrNull { it.precipProb }
            }
            Spacer(Modifier.weight(1f))
            Text("EXTENDED OUTLOOK", color = PanelColors.TextDim, fontSize = 15.sp, letterSpacing = 1.sp)
            if (next != null) {
                Text(
                    "${next.date.dayOfWeek.getDisplayName(DayStyle.FULL, locale)}: ${next.precipProb}% chance" +
                        if (next.precipSum >= 0.5) ", ${fmt.precip(next.precipSum)}" else "",
                    color = PanelColors.Text,
                    fontSize = 28.sp,
                )
            } else {
                Text("No rain in the ${w.daily.size}-day forecast", color = PanelColors.Text, fontSize = 28.sp)
            }
            return@Column
        }

        val kind = fmt.precipKind(p)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (p.ongoing) "$kind now" else "$kind arriving", color = PanelColors.Text, fontSize = 30.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(12.dp))
            if (p.severe) Badge("SEVERE", PanelColors.Critical)
            if (p.thunder) Badge("LIGHTNING", PanelColors.Storm)
            if (p.snow) Badge("SNOW", PanelColors.Cold)
        }
        if (p.ongoing) {
            Text(p.end?.let { "until ~${fmt.approxTime(it)}" } ?: "through tomorrow", color = PanelColors.Accent, fontSize = 50.sp, fontWeight = FontWeight.Light, lineHeight = 54.sp)
            Text(p.end?.let { "about ${fmt.duration(Duration.between(now, it))} more" } ?: "no break in sight", color = PanelColors.TextSecondary, fontSize = 22.sp)
        } else {
            Text("~${fmt.approxTime(p.start)}", color = PanelColors.Accent, fontSize = 50.sp, fontWeight = FontWeight.Light, lineHeight = 54.sp)
            Text(fmt.until(p.start, now), color = PanelColors.TextSecondary, fontSize = 22.sp)
        }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth()) {
            Stat("Expected", fmt.precip(p.totalMm), Modifier.weight(1f))
            Stat("Chance", "${p.maxProb}%", Modifier.weight(1f))
            Stat("Lasts", p.duration?.let(fmt::duration) ?: "12 h+", Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Stat("Heaviest", fmt.precip(p.peakRateMmH) + "/h", Modifier.weight(1f))
            Stat("Wind", fmt.speed(p.peakWind), Modifier.weight(1f))
            Stat("Gusts", fmt.speed(p.peakGust), Modifier.weight(1f), highlight = p.peakGust >= 60)
        }
    }
}

// ---------------------------------------------------------------- wind

@Composable
fun WindPanel(w: Weather, outlook: Outlook?, fmt: Fmt, now: Instant) {
    val hours = w.hourly.filter { it.time.isAfter(now) }.take(24)
    val event = outlook?.wind
    Split(
        first = { m ->
            Row(
                m.clip(RoundedCornerShape(22.dp)).background(PanelColors.Card).padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Compass(w.current.windDir, fmt.speedValue(w.current.wind), fmt.speedUnit, Modifier.size(220.dp))
                Spacer(Modifier.width(24.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("From the ${fmt.compass(w.current.windDir)}", color = PanelColors.Text, fontSize = 30.sp, fontWeight = FontWeight.Medium)
                    Text("Gusting ${fmt.speed(w.current.gust)}", color = PanelColors.Warm, fontSize = 26.sp)
                    if (event != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("Peak gusts ${fmt.speed(event.peakGust)}", color = PanelColors.Text, fontSize = 24.sp)
                        Text("around ${fmt.hour(event.peakAt)} from the ${fmt.compass(event.direction)}", color = PanelColors.TextSecondary, fontSize = 22.sp)
                    }
                    w.today?.let { Text("Today's max gust ${fmt.speed(it.gustMax)}", color = PanelColors.TextDim, fontSize = 20.sp) }
                }
            }
        },
        second = { m ->
            Column(m) {
                ChartLabel("WIND (SOLID) & GUSTS (DASHED) · ${fmt.speedUnit.uppercase()}")
                WindChart(hours, fmt, Modifier.fillMaxWidth().weight(1f).padding(top = 6.dp))
            }
        },
    )
}

@Composable
private fun Compass(directionFrom: Int, speed: Int, unit: String, modifier: Modifier) {
    val tm = rememberTextMeasurer()
    val cardinal = TextStyle(color = PanelColors.TextDim, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    val big = TextStyle(color = PanelColors.Text, fontSize = 52.sp, fontWeight = FontWeight.Light)
    val small = TextStyle(color = PanelColors.TextSecondary, fontSize = 18.sp)
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2 - 4.dp.toPx()
        drawCircle(PanelColors.Divider, r, c, style = Stroke(2.dp.toPx()))
        listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f).forEach { (t, a) ->
            val l = tm.measure(t, cardinal)
            val rad = Math.toRadians(a.toDouble() - 90)
            val p = Offset(c.x + (r - 16.dp.toPx()) * kotlin.math.cos(rad).toFloat(), c.y + (r - 16.dp.toPx()) * kotlin.math.sin(rad).toFloat())
            drawText(l, topLeft = Offset(p.x - l.size.width / 2, p.y - l.size.height / 2))
        }
        // The arrow points the way the wind blows: downwind of where it comes from.
        rotate(directionFrom + 180f, c) {
            val arrow = Path().apply {
                moveTo(c.x, c.y - r + 34.dp.toPx())
                lineTo(c.x - 13.dp.toPx(), c.y - r + 62.dp.toPx())
                lineTo(c.x + 13.dp.toPx(), c.y - r + 62.dp.toPx())
                close()
            }
            drawPath(arrow, PanelColors.Accent)
            drawLine(PanelColors.Accent.copy(alpha = 0.4f), Offset(c.x, c.y + r - 36.dp.toPx()), Offset(c.x, c.y + r - 58.dp.toPx()), 4.dp.toPx())
        }
        val v = tm.measure("$speed", big)
        val u = tm.measure(unit, small)
        drawText(v, topLeft = Offset(c.x - v.size.width / 2, c.y - v.size.height / 2 - 10.dp.toPx()))
        drawText(u, topLeft = Offset(c.x - u.size.width / 2, c.y + v.size.height / 2 - 14.dp.toPx()))
    }
}

// ---------------------------------------------------------------- shared bits

/** Side by side in landscape, stacked in portrait. */
@Composable
private fun Split(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    if (LocalLandscape.current) {
        Row(Modifier.fillMaxSize()) {
            first(Modifier.weight(0.9f).fillMaxHeight())
            Spacer(Modifier.width(28.dp))
            second(Modifier.weight(1.1f).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            first(Modifier.weight(1f).fillMaxWidth())
            Spacer(Modifier.height(20.dp))
            second(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun ColumnScope.ChartLabel(text: String) {
    Text(text, color = PanelColors.TextDim, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, highlight: Boolean = false) {
    Column(modifier) {
        Text(label.uppercase(), color = PanelColors.TextDim, fontSize = 13.sp, letterSpacing = 1.sp)
        Text(value, color = if (highlight) PanelColors.Warm else PanelColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(
        Modifier
            .padding(end = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.22f))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(text, color = color, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}
