package com.example.tabletpanel.weather

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Formatting in the display's chosen units and clock style. Inputs are always metric. */
class Fmt(val metric: Boolean, val clock24h: Boolean, val zone: ZoneId = ZoneId.systemDefault()) {
    private val timeFmt = DateTimeFormatter.ofPattern(if (clock24h) "H:mm" else "h:mm a", Locale.getDefault())
    private val hourFmt = DateTimeFormatter.ofPattern(if (clock24h) "HH:00" else "h a", Locale.getDefault())

    fun tempValue(c: Double): Int = (if (metric) c else c * 9 / 5 + 32).roundToInt()
    fun temp(c: Double) = "${tempValue(c)}°"

    fun speedValue(kmh: Double): Int = (if (metric) kmh else kmh * 0.621371).roundToInt()
    val speedUnit get() = if (metric) "km/h" else "mph"
    fun speed(kmh: Double) = "${speedValue(kmh)} $speedUnit"

    fun precip(mm: Double): String = if (metric) {
        if (mm < 10) "%.1f mm".format(mm) else "${mm.roundToInt()} mm"
    } else {
        val inches = mm / 25.4
        if (inches > 0 && inches < 0.01) "<0.01 in" else "%.2f in".format(inches)
    }

    fun time(i: Instant): String = timeFmt.format(i.atZone(zone))
    fun hour(i: Instant): String = hourFmt.format(i.atZone(zone))

    /** "about 3:15 PM": rounded to the quarter hour so a forecast doesn't look more precise than it is. */
    fun approxTime(i: Instant): String {
        val q = 15 * 60L
        return time(Instant.ofEpochSecond((i.epochSecond + q / 2) / q * q))
    }

    fun weekday(d: LocalDate, today: LocalDate): String = when (d) {
        today -> "Today"
        else -> d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    }

    fun duration(d: Duration): String {
        val mins = ((d.seconds + 59) / 60).coerceAtLeast(0)
        return when {
            mins < 60 -> "$mins min"
            mins % 60 == 0L || mins >= 6 * 60 -> "${(mins + 30) / 60} h"
            else -> "${mins / 60} h ${mins % 60} min"
        }
    }

    fun until(target: Instant, now: Instant): String =
        if (!target.isAfter(now.plusSeconds(60))) "now" else "in " + duration(Duration.between(now, target))

    fun compass(deg: Int): String = COMPASS[((deg % 360 + 360) % 360 * 16 + 180) / 360 % 16]

    /** One-line summary of the outlook, for the panel header and the server's device list. */
    fun headline(o: Outlook, now: Instant): String {
        val p = o.precip
        if (p != null && (p.ongoing || o.autoFocus == com.example.tabletpanel.data.Focus.RAIN)) {
            val kind = precipKind(p)
            return if (p.ongoing) {
                p.end?.let { "$kind until about ${approxTime(it)}" } ?: "$kind continuing"
            } else {
                "$kind expected around ${approxTime(p.start)}"
            }
        }
        o.wind?.let { return "Gusts to ${speed(it.peakGust)} around ${hour(it.peakAt)}" }
        if (p != null) return "${precipKind(p)} possible ${weekdayOrTime(p.start, now)}"
        return "Dry for the next day"
    }

    fun precipKind(p: PrecipEvent) = when {
        p.thunder -> "Thunderstorms"
        p.snow -> "Snow"
        p.totalMm < 2 && (p.duration?.toHours() ?: 99) <= 2 -> "Showers"
        else -> "Rain"
    }

    private fun weekdayOrTime(i: Instant, now: Instant): String {
        val day = i.atZone(zone).toLocalDate()
        val today = now.atZone(zone).toLocalDate()
        return when (day) {
            today -> "around ${hour(i)}"
            today.plusDays(1) -> "tomorrow ${hour(i)}"
            else -> day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        }
    }

    private companion object {
        val COMPASS = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
    }
}
