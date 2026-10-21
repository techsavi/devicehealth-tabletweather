package com.example.tabletpanel.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

val PanelJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

/** What the lower half of the display focuses on. Matches the server's FOCUS_MODES. */
enum class Focus(val key: String, val title: String) {
    AUTO("auto", "Auto"),
    FORECAST("forecast", "10-Day Forecast"),
    HOURLY("hourly", "Next 24 Hours"),
    RAIN("rain", "Rain & Storms"),
    RADAR("radar", "Radar"),
    WIND("wind", "Wind");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: AUTO
    }
}

@Serializable
data class PanelState(
    val rev: Int = 0,
    val serverTime: String? = null,
    val display: DisplayConfig = DisplayConfig(),
    val alerts: List<PanelAlert> = emptyList(),
    val notes: List<PanelNote> = emptyList(),
)

@Serializable
data class DisplayConfig(
    val focus: String = "auto",
    val focusUntil: String? = null,
    val units: String = "imperial",
    val clock24h: Boolean = false,
    val location: FixedLocation? = null,
    val radarZoom: Int = 7,
    val night: NightMode = NightMode(),
    val brightness: Float? = null,
) {
    val metric get() = units == "metric"

    /** The remotely chosen focus, or AUTO once its hold time has run out. */
    fun focusAt(now: Instant): Focus {
        val until = focusUntil?.let(::parseInstant)
        return if (until != null && !until.isAfter(now)) Focus.AUTO else Focus.of(focus)
    }
}

@Serializable
data class FixedLocation(val lat: Double, val lon: Double, val name: String? = null)

@Serializable
data class NightMode(
    val enabled: Boolean = false,
    /** Follow the ambient light sensor when the tablet has one; otherwise use [start]-[end]. */
    val useSensor: Boolean = true,
    /** Below this many lux the room counts as dark. */
    val darkLux: Float = 5f,
    val start: String = "22:00",
    val end: String = "06:30",
    val brightness: Float = 0.1f,
)

@Serializable
data class PanelAlert(
    val id: String,
    val title: String? = null,
    val message: String? = null,
    val severity: String = "info",
    val icon: String? = null,
    val sound: Boolean = false,
    val dismissible: Boolean = true,
    val source: String? = null,
    val createdAt: String? = null,
    val expiresAt: String? = null,
) {
    val critical get() = severity == "critical"
    fun expired(now: Instant) = expiresAt?.let(::parseInstant)?.let { !it.isAfter(now) } ?: false
}

@Serializable
data class PanelNote(val id: String, val text: String, val icon: String? = null)

@Serializable
data class ServerMessage(
    val type: String,
    val state: PanelState? = null,
    val command: String? = null,
)

fun parseInstant(s: String): Instant? = runCatching { Instant.parse(s) }.getOrNull()
