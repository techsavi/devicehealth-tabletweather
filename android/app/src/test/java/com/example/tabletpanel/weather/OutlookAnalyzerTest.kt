package com.example.tabletpanel.weather

import com.example.tabletpanel.data.Focus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class OutlookAnalyzerTest {
    // 10:20 local; the hour in progress ends at 11:00.
    private val now = Instant.parse("2026-10-03T16:20:00Z")
    private val firstHour = Instant.parse("2026-10-03T06:00:00Z") // local midnight-ish; hourly starts in the past

    private fun weather(
        hourly: (Int) -> HourPoint.() -> HourPoint = { { this } },
        minutely: List<Double> = List(24) { 0.0 },
        currentPrecip: Double = 0.0,
        currentCode: Int = 0,
    ): Weather {
        val hours = (0 until 72).map { i ->
            val base = HourPoint(firstHour.plusSeconds(i * 3600L), 15.0, 0, 0.0, 0, 10.0, 180, 18.0, true)
            hourly(i)(base)
        }
        val minuteStart = Instant.parse("2026-10-03T16:15:00Z")
        return Weather(
            fetchedAt = now,
            lat = 0.0,
            lon = 0.0,
            current = Current(now, 15.0, 14.0, 50, true, currentPrecip, currentCode, 20, 1013.0, 10.0, 180, 18.0),
            minutely = minutely.mapIndexed { i, p -> MinutePoint(minuteStart.plusSeconds(i * 900L), p) },
            hourly = hours,
            daily = (0 until 10).map { DayPoint(LocalDate.of(2026, 10, 3).plusDays(it.toLong()), 0, 20.0, 8.0, 0.0, 0, 0.0, 15.0, 25.0, null, null, 4.0) },
        )
    }

    /** Index into the synthetic hourly list of the hour ending at [utc] (e.g. "17:00"). */
    private fun idx(utc: String) = ((Instant.parse("2026-10-03T${utc}:00Z").epochSecond - firstHour.epochSecond) / 3600).toInt()

    @Test
    fun fairWeatherShowsForecast() {
        val o = OutlookAnalyzer.analyze(weather(), now)
        assertNull(o.precip)
        assertNull(o.wind)
        assertEquals(Focus.FORECAST, o.autoFocus)
    }

    @Test
    fun rainLaterTodayShowsRainPanelWithTimingAndTotals() {
        val start = idx("19:00")
        val w = weather(hourly = { i ->
            {
                if (i in start..start + 2) copy(precip = 2.0, precipProb = 80, code = 63, gust = 45.0) else this
            }
        })
        val o = OutlookAnalyzer.analyze(w, now)
        val p = assertNotNullAndGet(o.precip)
        assertEquals(Focus.RAIN, o.autoFocus)
        assertFalse(p.ongoing)
        // The 19:00 value covers 18:00-19:00, so rain starts around 18:00.
        assertEquals(Instant.parse("2026-10-03T18:00:00Z"), p.start)
        assertEquals(Instant.parse("2026-10-03T21:00:00Z"), p.end)
        assertEquals(6.0, p.totalMm, 0.001)
        assertEquals(80, p.maxProb)
        assertEquals(45.0, p.peakGust, 0.001)
        assertFalse(p.thunder)
    }

    @Test
    fun nowcastSharpensTheStartTime() {
        val start = idx("18:00")
        val slots = List(24) { if (it >= 5) 0.4 else 0.0 } // first wet slot ends 17:30
        val w = weather(hourly = { i -> { if (i in start..start + 1) copy(precip = 1.5, precipProb = 70, code = 61) else this } }, minutely = slots)
        val p = assertNotNullAndGet(OutlookAnalyzer.analyze(w, now).precip)
        assertEquals(Instant.parse("2026-10-03T17:15:00Z"), p.start)
    }

    @Test
    fun singleDryHourInsideASpellIsBridged() {
        val start = idx("19:00")
        val w = weather(hourly = { i ->
            { if (i == start || i == start + 2) copy(precip = 1.0, precipProb = 70, code = 61) else this }
        })
        val p = assertNotNullAndGet(OutlookAnalyzer.analyze(w, now).precip)
        assertEquals(Instant.parse("2026-10-03T21:00:00Z"), p.end)
    }

    @Test
    fun ongoingRainReportsWhenItEnds() {
        val current = idx("17:00")
        val w = weather(
            hourly = { i -> { if (i in current..current + 1) copy(precip = 3.0, precipProb = 90, code = 63) else this } },
            minutely = List(24) { if (it < 7) 0.8 else 0.0 },
            currentPrecip = 0.8,
            currentCode = 63,
        )
        val o = OutlookAnalyzer.analyze(w, now)
        val p = assertNotNullAndGet(o.precip)
        assertTrue(p.ongoing)
        assertEquals(now, p.start)
        assertEquals(Instant.parse("2026-10-03T18:00:00Z"), p.end)
        // 40 of the current hour's 60 minutes remain: 3.0 * 2/3 + 3.0
        assertEquals(5.0, p.totalMm, 0.01)
        assertEquals(Focus.RAIN, o.autoFocus)
    }

    @Test
    fun thunderstormsTomorrowMorningAreFlaggedSevere() {
        val start = idx("12:00") + 24 // ~20 h away
        val w = weather(hourly = { i -> { if (i == start) copy(precip = 6.0, precipProb = 60, code = 95, gust = 75.0) else this } })
        val o = OutlookAnalyzer.analyze(w, now)
        val p = assertNotNullAndGet(o.precip)
        assertTrue(p.thunder)
        assertTrue(p.severe)
        // Severe storms count as "soon" out to 24 h.
        assertEquals(Focus.RAIN, o.autoFocus)
    }

    @Test
    fun ordinaryRainBeyondTwelveHoursKeepsTheForecast() {
        val start = idx("12:00") + 24
        val w = weather(hourly = { i -> { if (i == start) copy(precip = 1.0, precipProb = 60, code = 61) else this } })
        val o = OutlookAnalyzer.analyze(w, now)
        assertNotNull(o.precip)
        assertEquals(Focus.FORECAST, o.autoFocus)
    }

    @Test
    fun strongGustsWithoutRainShowWindPanel() {
        val peak = idx("20:00")
        val w = weather(hourly = { i -> { if (i == peak) copy(wind = 30.0, gust = 65.0, windDir = 270) else this } })
        val o = OutlookAnalyzer.analyze(w, now)
        assertEquals(Focus.WIND, o.autoFocus)
        val wind = assertNotNullAndGet(o.wind)
        assertEquals(65.0, wind.peakGust, 0.001)
        assertEquals(270, wind.direction)
    }

    @Test
    fun headlinesReadNaturally() {
        val fmt = Fmt(metric = false, clock24h = false, zone = ZoneId.of("UTC"))
        val start = idx("19:00")
        val w = weather(hourly = { i -> { if (i in start..start + 2) copy(precip = 2.0, precipProb = 80, code = 63) else this } })
        assertEquals("Rain expected around 6:00 PM", fmt.headline(OutlookAnalyzer.analyze(w, now), now))
        assertEquals("Dry for the next day", fmt.headline(OutlookAnalyzer.analyze(weather(), now), now))
    }

    @Test
    fun formatsUnits() {
        val us = Fmt(metric = false, clock24h = false)
        val si = Fmt(metric = true, clock24h = true)
        assertEquals("72°", us.temp(22.2))
        assertEquals("22°", si.temp(22.2))
        assertEquals("10 mph", us.speed(16.0))
        assertEquals("0.25 in", us.precip(6.35))
        assertEquals("6.4 mm", si.precip(6.35))
        assertEquals("SW", us.compass(225))
        assertEquals("N", us.compass(355))
        assertEquals("2 h 15 min", us.duration(java.time.Duration.ofMinutes(135)))
    }

    private fun <T> assertNotNullAndGet(v: T?): T {
        assertNotNull(v)
        return v!!
    }
}
