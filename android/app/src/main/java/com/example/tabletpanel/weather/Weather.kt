package com.example.tabletpanel.weather

import java.time.Instant
import java.time.LocalDate

/** A forecast snapshot. Always metric (°C, km/h, mm); conversion happens only when formatting. */
data class Weather(
    val fetchedAt: Instant,
    val lat: Double,
    val lon: Double,
    val current: Current,
    /** 15-minute precipitation for the next few hours; each value is the total for the 15 min *ending* at [MinutePoint.time]. */
    val minutely: List<MinutePoint>,
    /** Hourly from local midnight today; precipitation is the total for the hour ending at [HourPoint.time]. */
    val hourly: List<HourPoint>,
    val daily: List<DayPoint>,
) {
    /** Hours from the one currently in progress (the first whose interval ends after [now]) onward. */
    fun hoursFrom(now: Instant): List<HourPoint> = hourly.filter { it.time.isAfter(now) }

    val today: DayPoint? get() = daily.firstOrNull()
}

data class Current(
    val time: Instant,
    val temp: Double,
    val feelsLike: Double,
    val humidity: Int,
    val isDay: Boolean,
    val precip: Double,
    val code: Int,
    val cloudCover: Int,
    val pressure: Double,
    val wind: Double,
    val windDir: Int,
    val gust: Double,
)

data class MinutePoint(val time: Instant, val precip: Double)

data class HourPoint(
    val time: Instant,
    val temp: Double,
    val precipProb: Int,
    val precip: Double,
    val code: Int,
    val wind: Double,
    val windDir: Int,
    val gust: Double,
    val isDay: Boolean,
)

data class DayPoint(
    val date: LocalDate,
    val code: Int,
    val tempMax: Double,
    val tempMin: Double,
    val precipSum: Double,
    val precipProb: Int,
    val precipHours: Double,
    val windMax: Double,
    val gustMax: Double,
    val sunrise: Instant?,
    val sunset: Instant?,
    val uvMax: Double,
)
