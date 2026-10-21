package com.example.tabletpanel.weather

import com.example.tabletpanel.data.Focus
import java.time.Duration
import java.time.Instant

/** A spell of rain/snow/storms, either happening now or the next one in the forecast. */
data class PrecipEvent(
    val ongoing: Boolean,
    val start: Instant,
    /** When it is expected to stop; null if it runs past the end of the forecast window. */
    val end: Instant?,
    val totalMm: Double,
    val maxProb: Int,
    val peakRateMmH: Double,
    val peakWind: Double,
    val peakGust: Double,
    val thunder: Boolean,
    val snow: Boolean,
) {
    val severe get() = thunder || peakGust >= 70 || totalMm >= 25
    val duration: Duration? get() = end?.let { Duration.between(start, it) }
}

data class WindEvent(val peakWind: Double, val peakGust: Double, val peakAt: Instant, val direction: Int)

data class Outlook(val precip: PrecipEvent?, val wind: WindEvent?, val autoFocus: Focus)

/**
 * Decides what the lower half of the display should show in auto mode:
 * rain or storms now or within 12 hours (24 for severe storms) -> rain panel,
 * strong wind within 12 hours -> wind panel, otherwise the long-range forecast.
 */
object OutlookAnalyzer {
    private const val HORIZON_HOURS = 36
    private const val SEARCH_HOURS = 24
    private const val WET_SLOT_MM = 0.1

    fun analyze(w: Weather, now: Instant): Outlook {
        val hours = w.hoursFrom(now).take(HORIZON_HOURS)
        val precip = precipEvent(w, hours, now)
        val wind = windEvent(hours.take(12))
        val soon = precip != null && (
            precip.ongoing ||
                precip.start.isBefore(now.plusSeconds(12 * 3600)) ||
                (precip.severe && precip.start.isBefore(now.plusSeconds(24 * 3600)))
            )
        val focus = when {
            soon -> Focus.RAIN
            wind != null -> Focus.WIND
            else -> Focus.FORECAST
        }
        return Outlook(precip, wind, focus)
    }

    fun isWet(h: HourPoint) =
        h.precip >= 0.2 ||
            (h.precipProb >= 50 && WeatherCodes.isPrecip(h.code)) ||
            (WeatherCodes.isThunder(h.code) && h.precipProb >= 30)

    private fun precipEvent(w: Weather, hours: List<HourPoint>, now: Instant): PrecipEvent? {
        if (hours.isEmpty()) return null
        val upcomingSlots = w.minutely.filter { it.time.isAfter(now.minusSeconds(15 * 60)) }
        val ongoing = w.current.precip >= WET_SLOT_MM || (upcomingSlots.firstOrNull()?.precip ?: 0.0) >= WET_SLOT_MM

        // A passing shower the hourly model barely registers: describe it from the 15-minute data.
        if (ongoing && !isWet(hours[0])) {
            val wetRun = upcomingSlots.takeWhile { it.precip >= WET_SLOT_MM }.ifEmpty { listOf(upcomingSlots.firstOrNull() ?: return null) }
            return PrecipEvent(
                ongoing = true,
                start = now,
                end = wetRun.last().time,
                totalMm = wetRun.sumOf { it.precip },
                maxProb = hours[0].precipProb,
                peakRateMmH = wetRun.maxOf { it.precip } * 4,
                peakWind = hours[0].wind,
                peakGust = maxOf(hours[0].gust, w.current.gust),
                thunder = WeatherCodes.isThunder(w.current.code),
                snow = WeatherCodes.isSnow(w.current.code),
            )
        }

        val startIdx = if (ongoing) 0 else hours.take(SEARCH_HOURS).indexOfFirst(::isWet).takeIf { it >= 0 } ?: return null

        // Extend through consecutive wet hours, bridging single dry hours inside a longer spell.
        var endIdx = startIdx
        while (endIdx + 1 < hours.size) {
            endIdx += when {
                isWet(hours[endIdx + 1]) -> 1
                endIdx + 2 < hours.size && isWet(hours[endIdx + 2]) -> 2
                else -> break
            }
        }
        val span = hours.subList(startIdx, endIdx + 1)

        val start = if (ongoing) {
            now
        } else {
            // Hourly values cover the hour *ending* at the timestamp; sharpen with 15-minute data when it reaches that far.
            val hourEnd = hours[startIdx].time
            val firstWetSlot = upcomingSlots.firstOrNull { it.precip >= WET_SLOT_MM && !it.time.isAfter(hourEnd) }
            maxOf(now, firstWetSlot?.time?.minusSeconds(15 * 60) ?: hourEnd.minusSeconds(3600))
        }

        // For a spell already under way, count only what's left of the current hour.
        val firstHourShare = if (ongoing) (Duration.between(now, hours[0].time).seconds / 3600.0).coerceIn(0.0, 1.0) else 1.0
        val total = span.mapIndexed { i, h -> if (i == 0) h.precip * firstHourShare else h.precip }.sum()

        return PrecipEvent(
            ongoing = ongoing,
            start = start,
            end = if (endIdx == hours.lastIndex) null else hours[endIdx].time,
            totalMm = total,
            maxProb = span.maxOf { it.precipProb },
            peakRateMmH = span.maxOf { it.precip },
            peakWind = span.maxOf { it.wind },
            peakGust = span.maxOf { it.gust },
            thunder = span.any { WeatherCodes.isThunder(it.code) },
            snow = span.count { WeatherCodes.isSnow(it.code) } * 2 > span.size,
        )
    }

    private fun windEvent(hours: List<HourPoint>): WindEvent? {
        val peak = hours.maxByOrNull { it.gust } ?: return null
        val maxWind = hours.maxOf { it.wind }
        if (peak.gust < 50 && maxWind < 35) return null
        return WindEvent(maxWind, peak.gust, peak.time, peak.windDir)
    }
}
