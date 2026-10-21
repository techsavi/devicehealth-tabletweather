package com.example.tabletpanel.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/** Fetches forecasts from open-meteo.com (free, no key; fine for personal, non-commercial use). */
class OpenMeteo(private val http: OkHttpClient = defaultClient) {

    suspend fun forecast(lat: Double, lon: Double): Weather = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", "%.4f".format(java.util.Locale.US, lat))
            .addQueryParameter("longitude", "%.4f".format(java.util.Locale.US, lon))
            .addQueryParameter("timeformat", "unixtime")
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("forecast_days", "10")
            .addQueryParameter("forecast_minutely_15", "24")
            .addQueryParameter("current", CURRENT)
            .addQueryParameter("minutely_15", "precipitation")
            .addQueryParameter("hourly", HOURLY)
            .addQueryParameter("daily", DAILY)
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { res ->
            val body = res.body?.string() ?: throw IOException("empty response")
            if (!res.isSuccessful) throw IOException("open-meteo ${res.code}: ${body.take(200)}")
            parse(body)
        }
    }

    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        private const val CURRENT = "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation," +
            "weather_code,cloud_cover,pressure_msl,wind_speed_10m,wind_direction_10m,wind_gusts_10m"
        private const val HOURLY = "temperature_2m,precipitation_probability,precipitation,weather_code," +
            "wind_speed_10m,wind_direction_10m,wind_gusts_10m,is_day"
        private const val DAILY = "weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum," +
            "precipitation_probability_max,precipitation_hours,wind_speed_10m_max,wind_gusts_10m_max," +
            "sunrise,sunset,uv_index_max"

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(body: String, fetchedAt: Instant = Instant.now()): Weather {
            val r = json.decodeFromString<Response>(body)
            val offset = ZoneOffset.ofTotalSeconds(r.utcOffsetSeconds)
            val c = r.current
            val h = r.hourly
            val d = r.daily
            val m = r.minutely

            return Weather(
                fetchedAt = fetchedAt,
                lat = r.latitude,
                lon = r.longitude,
                current = Current(
                    time = Instant.ofEpochSecond(c.time),
                    temp = c.temperature,
                    feelsLike = c.apparent ?: c.temperature,
                    humidity = c.humidity ?: 0,
                    isDay = c.isDay == 1,
                    precip = c.precipitation ?: 0.0,
                    code = c.weatherCode ?: 0,
                    cloudCover = c.cloudCover ?: 0,
                    pressure = c.pressure ?: 0.0,
                    wind = c.windSpeed ?: 0.0,
                    windDir = c.windDirection ?: 0,
                    gust = c.windGusts ?: 0.0,
                ),
                minutely = m?.time.orEmpty().mapIndexed { i, t ->
                    MinutePoint(Instant.ofEpochSecond(t), m!!.precipitation.getOrNull(i) ?: 0.0)
                },
                hourly = h.time.indices.mapNotNull { i ->
                    HourPoint(
                        time = Instant.ofEpochSecond(h.time[i]),
                        temp = h.temperature.getOrNull(i) ?: return@mapNotNull null,
                        precipProb = h.precipProbability.getOrNull(i) ?: 0,
                        precip = h.precipitation.getOrNull(i) ?: 0.0,
                        code = h.weatherCode.getOrNull(i) ?: 0,
                        wind = h.windSpeed.getOrNull(i) ?: 0.0,
                        windDir = h.windDirection.getOrNull(i) ?: 0,
                        gust = h.windGusts.getOrNull(i) ?: 0.0,
                        isDay = (h.isDay.getOrNull(i) ?: 1) == 1,
                    )
                },
                daily = d.time.indices.mapNotNull { i ->
                    DayPoint(
                        // Daily timestamps are local midnight at the forecast location.
                        date = Instant.ofEpochSecond(d.time[i]).atOffset(offset).toLocalDate(),
                        code = d.weatherCode.getOrNull(i) ?: 0,
                        tempMax = d.tempMax.getOrNull(i) ?: return@mapNotNull null,
                        tempMin = d.tempMin.getOrNull(i) ?: return@mapNotNull null,
                        precipSum = d.precipSum.getOrNull(i) ?: 0.0,
                        precipProb = d.precipProbMax.getOrNull(i) ?: 0,
                        precipHours = d.precipHours.getOrNull(i) ?: 0.0,
                        windMax = d.windMax.getOrNull(i) ?: 0.0,
                        gustMax = d.gustMax.getOrNull(i) ?: 0.0,
                        sunrise = d.sunrise.getOrNull(i)?.let(Instant::ofEpochSecond),
                        sunset = d.sunset.getOrNull(i)?.let(Instant::ofEpochSecond),
                        uvMax = d.uvMax.getOrNull(i) ?: 0.0,
                    )
                },
            )
        }
    }

    @Serializable
    private data class Response(
        val latitude: Double,
        val longitude: Double,
        @SerialName("utc_offset_seconds") val utcOffsetSeconds: Int = 0,
        val current: CurrentBlock,
        @SerialName("minutely_15") val minutely: MinutelyBlock? = null,
        val hourly: HourlyBlock,
        val daily: DailyBlock,
    )

    @Serializable
    private data class CurrentBlock(
        val time: Long,
        @SerialName("temperature_2m") val temperature: Double,
        @SerialName("apparent_temperature") val apparent: Double? = null,
        @SerialName("relative_humidity_2m") val humidity: Int? = null,
        @SerialName("is_day") val isDay: Int? = null,
        val precipitation: Double? = null,
        @SerialName("weather_code") val weatherCode: Int? = null,
        @SerialName("cloud_cover") val cloudCover: Int? = null,
        @SerialName("pressure_msl") val pressure: Double? = null,
        @SerialName("wind_speed_10m") val windSpeed: Double? = null,
        @SerialName("wind_direction_10m") val windDirection: Int? = null,
        @SerialName("wind_gusts_10m") val windGusts: Double? = null,
    )

    @Serializable
    private data class MinutelyBlock(
        val time: List<Long> = emptyList(),
        val precipitation: List<Double?> = emptyList(),
    )

    @Serializable
    private data class HourlyBlock(
        val time: List<Long>,
        @SerialName("temperature_2m") val temperature: List<Double?> = emptyList(),
        @SerialName("precipitation_probability") val precipProbability: List<Int?> = emptyList(),
        val precipitation: List<Double?> = emptyList(),
        @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
        @SerialName("wind_speed_10m") val windSpeed: List<Double?> = emptyList(),
        @SerialName("wind_direction_10m") val windDirection: List<Int?> = emptyList(),
        @SerialName("wind_gusts_10m") val windGusts: List<Double?> = emptyList(),
        @SerialName("is_day") val isDay: List<Int?> = emptyList(),
    )

    @Serializable
    private data class DailyBlock(
        val time: List<Long>,
        @SerialName("weather_code") val weatherCode: List<Int?> = emptyList(),
        @SerialName("temperature_2m_max") val tempMax: List<Double?> = emptyList(),
        @SerialName("temperature_2m_min") val tempMin: List<Double?> = emptyList(),
        @SerialName("precipitation_sum") val precipSum: List<Double?> = emptyList(),
        @SerialName("precipitation_probability_max") val precipProbMax: List<Int?> = emptyList(),
        @SerialName("precipitation_hours") val precipHours: List<Double?> = emptyList(),
        @SerialName("wind_speed_10m_max") val windMax: List<Double?> = emptyList(),
        @SerialName("wind_gusts_10m_max") val gustMax: List<Double?> = emptyList(),
        val sunrise: List<Long?> = emptyList(),
        val sunset: List<Long?> = emptyList(),
        @SerialName("uv_index_max") val uvMax: List<Double?> = emptyList(),
    )
}
