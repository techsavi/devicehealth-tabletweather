package com.example.tabletpanel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import com.example.tabletpanel.weather.Sky
import com.example.tabletpanel.weather.WeatherCodes
import kotlin.math.cos
import kotlin.math.sin

/** Weather glyphs drawn in code: crisp at any size and no icon-font dependency. */
@Composable
fun WeatherIcon(code: Int, isDay: Boolean, modifier: Modifier = Modifier) {
    val sky = WeatherCodes.sky(code)
    Canvas(modifier) {
        val s = size.minDimension
        translate((size.width - s) / 2, (size.height - s) / 2) {
            when (sky) {
                Sky.CLEAR -> if (isDay) sun(Offset(s * .5f, s * .5f), s * .22f, s) else moon(Offset(s * .5f, s * .5f), s * .3f)
                Sky.PARTLY -> {
                    if (isDay) sun(Offset(s * .36f, s * .36f), s * .17f, s * .78f) else moon(Offset(s * .38f, s * .36f), s * .22f)
                    cloud(s, dy = s * .1f, color = CloudLight)
                }
                Sky.CLOUDY -> {
                    cloud(s, dx = -s * .1f, dy = -s * .12f, scale = .7f, color = CloudDark)
                    cloud(s, dy = s * .06f, color = CloudLight)
                }
                Sky.FOG -> {
                    cloud(s, dy = -s * .1f, scale = .85f, color = CloudDark)
                    for (i in 0..2) {
                        val y = s * (.66f + i * .1f)
                        drawLine(CloudLight, Offset(s * (.18f + i * .04f), y), Offset(s * (.82f - i * .04f), y), s * .045f, StrokeCap.Round)
                    }
                }
                Sky.DRIZZLE -> {
                    cloud(s, dy = -s * .1f, color = CloudLight)
                    drops(s, count = 3, length = .07f)
                }
                Sky.RAIN -> {
                    cloud(s, dy = -s * .1f, color = CloudGrey)
                    drops(s, count = 4, length = .14f)
                }
                Sky.SLEET -> {
                    cloud(s, dy = -s * .1f, color = CloudGrey)
                    drops(s, count = 2, length = .12f)
                    flakes(s, offset = .5f)
                }
                Sky.SNOW -> {
                    cloud(s, dy = -s * .1f, color = CloudLight)
                    flakes(s)
                }
                Sky.THUNDER -> {
                    cloud(s, dy = -s * .12f, color = CloudStorm)
                    bolt(s)
                    drops(s, count = 2, length = .1f, xs = listOf(.3f, .74f))
                }
            }
        }
    }
}

private val CloudLight = Color(0xFFEFF3F8)
private val CloudGrey = Color(0xFFB9C3CF)
private val CloudDark = Color(0xFF8E9AA8)
private val CloudStorm = Color(0xFF7D8696)

private fun DrawScope.sun(c: Offset, r: Float, box: Float) {
    val rayIn = r * 1.35f
    val rayOut = r * 1.85f
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        val dx = cos(a).toFloat()
        val dy = sin(a).toFloat()
        drawLine(PanelColors.Sun, Offset(c.x + dx * rayIn, c.y + dy * rayIn), Offset(c.x + dx * rayOut, c.y + dy * rayOut), box * .05f, StrokeCap.Round)
    }
    drawCircle(PanelColors.Sun, r, c)
}

private fun DrawScope.moon(c: Offset, r: Float) {
    val full = Path().apply { addOval(Rect(c, r)) }
    val bite = Path().apply { addOval(Rect(Offset(c.x + r * .55f, c.y - r * .35f), r * .9f)) }
    drawPath(Path.combine(PathOperation.Difference, full, bite), Color(0xFFE8E2C8))
}

/** A cumulus made of overlapping circles on a flat base, centred in an s-by-s box. */
private fun DrawScope.cloud(s: Float, dx: Float = 0f, dy: Float = 0f, scale: Float = 1f, color: Color) {
    val w = s * .7f * scale
    val h = s * .3f * scale
    val left = (s - w) / 2 + dx + (1 - scale) * s * .15f
    val bottom = s * .68f + dy
    drawRoundRect(color, Offset(left, bottom - h * .62f), Size(w, h * .62f), CornerRadius(h * .31f))
    drawCircle(color, h * .52f, Offset(left + w * .33f, bottom - h * .55f))
    drawCircle(color, h * .72f, Offset(left + w * .6f, bottom - h * .7f))
}

private fun DrawScope.drops(s: Float, count: Int, length: Float, xs: List<Float>? = null) {
    val positions = xs ?: List(count) { .3f + it * (.4f / (count - 1).coerceAtLeast(1)) }
    positions.forEachIndexed { i, x ->
        val top = s * (.68f + (i % 2) * .05f)
        drawLine(PanelColors.Rain, Offset(s * x, top), Offset(s * (x - .04f), top + s * length), s * .05f, StrokeCap.Round)
    }
}

private fun DrawScope.flakes(s: Float, offset: Float = 0f) {
    val xs = if (offset > 0) listOf(.55f, .72f) else listOf(.32f, .5f, .68f)
    xs.forEachIndexed { i, x ->
        val c = Offset(s * x, s * (.75f + (i % 2) * .08f))
        val r = s * .045f
        for (k in 0 until 3) {
            val a = Math.toRadians(k * 60.0)
            val d = Offset(cos(a).toFloat() * r, sin(a).toFloat() * r)
            drawLine(Color.White, c - d, c + d, s * .022f, StrokeCap.Round)
        }
    }
}

private fun DrawScope.bolt(s: Float) {
    val p = Path().apply {
        moveTo(s * .55f, s * .5f)
        lineTo(s * .4f, s * .74f)
        lineTo(s * .5f, s * .74f)
        lineTo(s * .44f, s * .94f)
        lineTo(s * .64f, s * .66f)
        lineTo(s * .53f, s * .66f)
        lineTo(s * .6f, s * .5f)
        close()
    }
    drawPath(p, PanelColors.Sun)
}
