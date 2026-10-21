package com.example.tabletpanel

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.tabletpanel.data.Coordinates
import com.example.tabletpanel.data.DarknessDetector
import com.example.tabletpanel.data.LightSensor
import com.example.tabletpanel.data.Discovery
import com.example.tabletpanel.data.DisplayConfig
import com.example.tabletpanel.data.Focus
import com.example.tabletpanel.data.LocationSource
import com.example.tabletpanel.data.PanelAlert
import com.example.tabletpanel.data.ServerClient
import com.example.tabletpanel.data.ServerStatus
import com.example.tabletpanel.data.Settings
import com.example.tabletpanel.weather.Fmt
import com.example.tabletpanel.weather.OpenMeteo
import com.example.tabletpanel.weather.Outlook
import com.example.tabletpanel.weather.OutlookAnalyzer
import com.example.tabletpanel.weather.Weather
import com.example.tabletpanel.weather.WeatherCodes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalTime
import kotlin.math.roundToInt

/** Where the forecast is for, and whether that came from the tablet or the server. */
data class PanelLocation(val coords: Coordinates, val fromServer: Boolean, val name: String?)

class PanelViewModel(app: Application) : AndroidViewModel(app) {
    val settings = Settings(app)
    private val locationSource = LocationSource(app)
    private val openMeteo = OpenMeteo()
    private val server = ServerClient(settings, Discovery(app), viewModelScope)
    private val sound = AlertSound(app)
    private val light = LightSensor(app)

    val panel = server.state
    val serverStatus: StateFlow<ServerStatus> = server.status

    /** Ticks every 15 s so anything time-based (outlook, focus holds, alert expiry, night mode) stays current. */
    val now: StateFlow<Instant> = flow {
        while (true) {
            emit(Instant.now())
            delay(15_000)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Instant.now())

    private val deviceCoords = MutableStateFlow<Coordinates?>(null)
    private val geocodedName = MutableStateFlow<Pair<Coordinates, String?>?>(null)
    private val _locationProblem = MutableStateFlow<String?>(null)
    val locationProblem: StateFlow<String?> = _locationProblem

    val location: StateFlow<PanelLocation?> =
        combine(panel.map { it.display.location }.distinctUntilChanged(), deviceCoords, geocodedName) { fixed, device, geo ->
            if (fixed != null) {
                val c = Coordinates(fixed.lat, fixed.lon)
                PanelLocation(c, true, fixed.name ?: geo?.takeIf { it.first == c }?.second)
            } else {
                device?.let { PanelLocation(it, false, geo?.takeIf { g -> g.first == it }?.second) }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _weather = MutableStateFlow<Weather?>(null)
    val weather: StateFlow<Weather?> = _weather
    private val _weatherError = MutableStateFlow<String?>(null)
    val weatherError: StateFlow<String?> = _weatherError
    private val refreshRequests = MutableStateFlow(0)

    val outlook: StateFlow<Outlook?> = combine(weather, now) { w, t -> w?.let { OutlookAnalyzer.analyze(it, t) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The panel actually shown: the server's choice, or the outlook's pick when that is auto. */
    val focus: StateFlow<Focus> = combine(panel, outlook, now) { p, o, t ->
        p.display.focusAt(t).takeIf { it != Focus.AUTO } ?: o?.autoFocus ?: Focus.FORECAST
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Focus.FORECAST)

    private val dismissed = MutableStateFlow<Set<String>>(emptySet())
    val alerts: StateFlow<List<PanelAlert>> = combine(panel, dismissed, now) { p, gone, t ->
        p.alerts.filter { it.id !in gone && !it.expired(t) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val lux = MutableStateFlow<Float?>(null)
    private val darkness = DarknessDetector()

    /** Room is dark per the light sensor; re-evaluated on each reading and each tick so the dim delay can elapse. */
    private val roomDark: StateFlow<Boolean> = combine(lux, panel.map { it.display.night.darkLux }, now) { l, threshold, _ ->
        l?.let { darkness.update(it, threshold, System.currentTimeMillis()) } ?: false
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Why the screen is dimmed ("light sensor" or "schedule"), or null when it isn't. */
    val dimReason: StateFlow<String?> = combine(panel, roomDark, now) { p, dark, _ ->
        val night = p.display.night
        when {
            !night.enabled -> null
            night.useSensor && light.available -> if (dark) "light sensor" else null
            inSchedule(p.display) -> "schedule"
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val nightActive: StateFlow<Boolean> = dimReason.map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Window brightness to apply, or null to follow the system setting. */
    val brightness: StateFlow<Float?> = combine(panel, nightActive) { p, night ->
        if (night) p.display.night.brightness else p.display.brightness
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _identify = MutableStateFlow(false)
    val identify: StateFlow<Boolean> = _identify

    private val _restart = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val restartRequests: SharedFlow<Unit> = _restart

    init {
        server.start()

        viewModelScope.launch { light.readings().collect { lux.value = it } }

        // A wall display doesn't move: refresh every 30 minutes, but retry soon while there's no fix.
        viewModelScope.launch {
            while (true) {
                updateLocation()
                delay(if (deviceCoords.value == null) 2 * 60_000L else 30 * 60_000L)
            }
        }

        // Re-fetch whenever the location moves ~1 km, a refresh is requested, or 10 minutes pass.
        viewModelScope.launch {
            val key = location.map { l -> l?.coords?.let { Coordinates(round2(it.lat), round2(it.lon)) } }.distinctUntilChanged()
            combine(key, refreshRequests) { c, _ -> c }.collectLatest { c ->
                if (c == null) return@collectLatest
                while (true) {
                    val ok = fetchWeather(c)
                    delay(if (ok) 10 * 60_000L else 60_000L)
                }
            }
        }

        viewModelScope.launch {
            location.map { it?.coords }.distinctUntilChanged().collectLatest { c ->
                if (c != null && geocodedName.value?.first != c) geocodedName.value = c to locationSource.placeName(c)
            }
        }

        viewModelScope.launch {
            server.commands.collect { cmd ->
                when (cmd) {
                    "refresh-weather" -> refreshWeather()
                    "identify" -> launch {
                        _identify.value = true
                        delay(8_000)
                        _identify.value = false
                    }
                    "reload" -> {
                        updateLocation()
                        refreshWeather()
                        _restart.tryEmit(Unit)
                    }
                }
            }
        }

        // Chime for alerts that arrive while the app is running (not ones restored at startup).
        viewModelScope.launch {
            var seen = panel.value.alerts.map { it.id }.toSet()
            alerts.collect { list ->
                val fresh = list.filter { it.id !in seen }
                seen = seen + list.map { it.id }
                fresh.filter { it.sound }.maxByOrNull { severityRank(it.severity) }?.let { sound.play(it.severity) }
            }
        }

        // Report what the tablet is showing so the web UI can display it.
        viewModelScope.launch {
            combine(serverStatus, weather, focus, location, merge(outlook, dimReason)) { s, _, _, _, _ -> s }.collectLatest { s ->
                if (s !is ServerStatus.Connected) return@collectLatest
                while (true) {
                    sendStatus()
                    delay(60_000)
                }
            }
        }
    }

    fun refreshWeather() = refreshRequests.update { it + 1 }

    fun refreshLocation() {
        viewModelScope.launch { updateLocation() }
    }

    private suspend fun updateLocation() {
        if (!locationSource.hasPermission()) {
            _locationProblem.value = "Location permission not granted. Allow it, or set a location in the web UI."
            return
        }
        val c = locationSource.current()
        if (c == null) {
            if (deviceCoords.value == null) _locationProblem.value = "Tablet location unavailable. Set a location in the web UI."
        } else {
            _locationProblem.value = null
            deviceCoords.value = c
        }
    }

    fun dismiss(alert: PanelAlert) {
        dismissed.update { it + alert.id }
        server.send(buildJsonObject {
            put("type", "dismiss")
            put("alertId", alert.id)
        })
    }

    fun saveSettings(serverUrl: String, apiKey: String, deviceName: String) {
        settings.serverUrl = serverUrl
        settings.apiKey = apiKey
        settings.deviceName = deviceName
        if (serverUrl.isBlank()) settings.lastDiscoveredUrl = null
        server.start()
    }

    fun fmt(display: DisplayConfig = panel.value.display) = Fmt(display.metric, display.clock24h)

    private suspend fun fetchWeather(c: Coordinates): Boolean = try {
        _weather.value = openMeteo.forecast(c.lat, c.lon)
        _weatherError.value = null
        true
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w("PanelViewModel", "weather fetch failed", e)
        _weatherError.value = "Weather update failed: ${e.message ?: e.javaClass.simpleName}"
        false
    }

    private fun sendStatus() {
        val w = weather.value
        val f = fmt()
        val loc = location.value
        val battery = getApplication<Application>().registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        server.send(buildJsonObject {
            put("type", "status")
            put("focus", focus.value.key)
            outlook.value?.let {
                put("autoFocus", it.autoFocus.key)
                put("outlook", f.headline(it, Instant.now()))
            }
            if (w != null) {
                put("temperature", f.tempValue(w.current.temp))
                put("condition", WeatherCodes.describe(w.current.code))
                put("weatherUpdated", w.fetchedAt.toString())
            }
            weatherError.value?.let { put("weatherError", it) }
            put("lightSensor", light.available)
            lux.value?.let { put("lux", it.roundToInt()) }
            dimReason.value?.let { put("dimmed", it) }
            if (loc != null) {
                put("lat", loc.coords.lat)
                put("lon", loc.coords.lon)
                put("locationSource", if (loc.fromServer) "server" else "tablet")
                loc.name?.let { put("place", it) }
            }
            if (battery != null) {
                val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0) put("battery", (level * 100f / scale).roundToInt())
                put("charging", battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
            }
        })
    }

    override fun onCleared() {
        sound.release()
    }

    private companion object {
        fun round2(v: Double) = (v * 100).roundToInt() / 100.0

        fun severityRank(s: String) = when (s) {
            "critical" -> 2
            "warning" -> 1
            else -> 0
        }

        fun inSchedule(d: DisplayConfig): Boolean {
            val start = runCatching { LocalTime.parse(d.night.start) }.getOrNull() ?: return false
            val end = runCatching { LocalTime.parse(d.night.end) }.getOrNull() ?: return false
            val t = LocalTime.now()
            return if (start <= end) t >= start && t < end else t >= start || t < end
        }
    }
}
