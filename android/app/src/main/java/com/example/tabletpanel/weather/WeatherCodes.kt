package com.example.tabletpanel.weather

/** Icon families for WMO weather codes, as used by Open-Meteo. */
enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, SLEET, THUNDER }

object WeatherCodes {
    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45 -> "Fog"
        48 -> "Freezing fog"
        51 -> "Light drizzle"
        53 -> "Drizzle"
        55 -> "Heavy drizzle"
        56, 57 -> "Freezing drizzle"
        61 -> "Light rain"
        63 -> "Rain"
        65 -> "Heavy rain"
        66, 67 -> "Freezing rain"
        71 -> "Light snow"
        73 -> "Snow"
        75 -> "Heavy snow"
        77 -> "Snow grains"
        80 -> "Light showers"
        81 -> "Showers"
        82 -> "Heavy showers"
        85 -> "Snow showers"
        86 -> "Heavy snow showers"
        95 -> "Thunderstorms"
        96, 99 -> "Storms with hail"
        else -> "Unknown"
    }

    fun sky(code: Int): Sky = when (code) {
        0 -> Sky.CLEAR
        1, 2 -> Sky.PARTLY
        3 -> Sky.CLOUDY
        45, 48 -> Sky.FOG
        51, 53, 55 -> Sky.DRIZZLE
        56, 57, 66, 67 -> Sky.SLEET
        61, 63, 65, 80, 81, 82 -> Sky.RAIN
        71, 73, 75, 77, 85, 86 -> Sky.SNOW
        95, 96, 99 -> Sky.THUNDER
        else -> Sky.CLOUDY
    }

    fun isPrecip(code: Int) = code >= 51
    fun isThunder(code: Int) = code >= 95
    fun isSnow(code: Int) = code in 71..77 || code == 85 || code == 86
}
