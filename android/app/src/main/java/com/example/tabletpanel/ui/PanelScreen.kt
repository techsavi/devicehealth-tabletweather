package com.example.tabletpanel.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.tabletpanel.PanelLocation
import com.example.tabletpanel.PanelViewModel
import com.example.tabletpanel.data.DisplayConfig
import com.example.tabletpanel.data.Focus
import com.example.tabletpanel.data.PanelNote
import com.example.tabletpanel.data.ServerStatus
import com.example.tabletpanel.data.parseInstant
import com.example.tabletpanel.weather.Fmt
import com.example.tabletpanel.weather.Outlook
import com.example.tabletpanel.weather.Weather
import com.example.tabletpanel.weather.WeatherCodes
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun PanelScreen(vm: PanelViewModel, onRequestLocation: () -> Unit, onExit: () -> Unit) {
    val panel by vm.panel.collectAsStateWithLifecycle()
    val weather by vm.weather.collectAsStateWithLifecycle()
    val weatherError by vm.weatherError.collectAsStateWithLifecycle()
    val locationProblem by vm.locationProblem.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    val outlook by vm.outlook.collectAsStateWithLifecycle()
    val focus by vm.focus.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val status by vm.serverStatus.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val night by vm.nightActive.collectAsStateWithLifecycle()
    val identify by vm.identify.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val display = panel.display
    val fmt = remember(display.metric, display.clock24h) { Fmt(display.metric, display.clock24h) }
    val w = weather

    PanelTheme {
        ScaledToReference {
            val landscape = LocalLandscape.current
            Box(
                Modifier
                    .fillMaxSize()
                    .background(skyBrush(w?.let { WeatherCodes.sky(it.current.code) }, w?.current?.isDay ?: true)),
            ) {
                Column(Modifier.fillMaxSize().padding(horizontal = 32.dp)) {
                    TopSection(
                        weather = w,
                        fmt = fmt,
                        location = location,
                        problem = weatherError.takeIf { w == null } ?: locationProblem.takeIf { location == null },
                        now = now,
                        onLongPress = { showSettings = true },
                        modifier = if (landscape) Modifier.weight(1.2f) else Modifier,
                    )
                    Box(Modifier.fillMaxWidth().height(1.dp).background(PanelColors.Divider))
                    FocusSection(
                        focus = focus,
                        display = display,
                        weather = w,
                        outlook = outlook,
                        location = location,
                        fmt = fmt,
                        now = now,
                        modifier = Modifier.weight(1f),
                    )
                    Footer(status, panel.notes, w, weatherError, fmt)
                }

                if (night) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))

                AlertOverlay(alerts, fmt, onDismiss = vm::dismiss)
                if (identify) IdentifyOverlay(vm.settings.deviceName, status)
                if (showSettings) {
                    SettingsDialog(
                        settings = vm.settings,
                        status = status,
                        locationProblem = locationProblem,
                        onSave = { url, key, name ->
                            vm.saveSettings(url, key, name)
                            showSettings = false
                        },
                        onRequestLocation = onRequestLocation,
                        onExit = onExit,
                        onDismiss = { showSettings = false },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- top half

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopSection(
    weather: Weather?,
    fmt: Fmt,
    location: PanelLocation?,
    problem: String?,
    now: Instant,
    onLongPress: () -> Unit,
    modifier: Modifier,
) {
    val landscape = LocalLandscape.current
    Column(
        modifier
            .fillMaxWidth()
            .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}, onLongClick = onLongPress)
            .padding(top = 16.dp, bottom = 10.dp),
    ) {
        Row(
            if (landscape) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Clock(fmt, weather, location, Modifier.weight(if (landscape) 1.15f else 1f))
            if (weather != null) {
                CurrentConditions(weather, fmt, Modifier.weight(1f))
            } else {
                Text(
                    problem ?: "Loading weather…",
                    color = PanelColors.TextSecondary,
                    fontSize = 26.sp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (weather != null) NextHours(weather, fmt, now)
    }
}

@Composable
private fun Clock(fmt: Fmt, weather: Weather?, location: PanelLocation?, modifier: Modifier) {
    val landscape = LocalLandscape.current
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now()
            delay(1000 - (System.currentTimeMillis() % 1000))
        }
    }
    val locale = LocalConfiguration.current.locales[0]
    val local = now.atZone(ZoneId.systemDefault())
    val time = DateTimeFormatter.ofPattern(if (fmt.clock24h) "H:mm" else "h:mm", locale).format(local)
    val ampm = if (fmt.clock24h) null else DateTimeFormatter.ofPattern("a", locale).format(local)
    val date = DateTimeFormatter.ofPattern("EEEE, MMMM d", locale).format(local)

    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(time, color = PanelColors.Text, fontSize = if (landscape) 156.sp else 128.sp, fontWeight = FontWeight.Light, lineHeight = 150.sp, maxLines = 1)
            if (ampm != null) {
                Text(ampm, color = PanelColors.TextSecondary, fontSize = 40.sp, modifier = Modifier.padding(start = 10.dp, bottom = 24.dp))
            }
        }
        Text(date, color = PanelColors.TextSecondary, fontSize = 34.sp, maxLines = 1)
        val today = weather?.today
        val sun = listOfNotNull(
            today?.sunrise?.let { "Sunrise ${fmt.time(it)}" },
            today?.sunset?.let { "Sunset ${fmt.time(it)}" },
        ).joinToString("   ·   ")
        // Landscape has room for one line; in portrait each column is half as wide, so stack them.
        val lines = if (landscape) {
            listOf(listOfNotNull(location?.name, sun.ifEmpty { null }).joinToString("   ·   "))
        } else {
            listOfNotNull(location?.name, sun.ifEmpty { null })
        }.filter { it.isNotEmpty() }
        lines.forEachIndexed { i, line ->
            Text(
                line,
                color = PanelColors.TextDim,
                fontSize = 22.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = if (i == 0) 8.dp else 2.dp),
            )
        }
    }
}

@Composable
private fun CurrentConditions(w: Weather, fmt: Fmt, modifier: Modifier) {
    val c = w.current
    val today = w.today
    Column(modifier, horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WeatherIcon(c.code, c.isDay, Modifier.size(140.dp))
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(fmt.temp(c.temp), color = PanelColors.Text, fontSize = 120.sp, fontWeight = FontWeight.Light, lineHeight = 116.sp)
                Text(WeatherCodes.describe(c.code), color = PanelColors.TextSecondary, fontSize = 30.sp, maxLines = 1)
            }
        }
        val landscape = LocalLandscape.current
        val line1 = buildList {
            add("Feels like ${fmt.temp(c.feelsLike)}")
            if (today != null) add("H ${fmt.temp(today.tempMax)}  L ${fmt.temp(today.tempMin)}")
        }.joinToString("   ·   ")
        Text(line1, color = PanelColors.TextSecondary, fontSize = 24.sp, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
        val gust = if (c.gust >= c.wind + 8) ", gusts ${fmt.speedValue(c.gust)}" else ""
        val wind = "Wind ${fmt.compass(c.windDir)} ${fmt.speed(c.wind)}$gust"
        val humidity = "Humidity ${c.humidity}%"
        (if (landscape) listOf("$wind   ·   $humidity") else listOf(wind, humidity)).forEach {
            Text(it, color = PanelColors.TextDim, fontSize = 22.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun NextHours(w: Weather, fmt: Fmt, now: Instant) {
    val count = if (LocalLandscape.current) 10 else 8
    // Hourly temps are instantaneous at the timestamp; start with the next full hour.
    val hours = w.hourly.filter { it.time.isAfter(now) }.take(count)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(PanelColors.Card)
            .padding(vertical = 6.dp),
    ) {
        hours.forEach { h ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(fmt.hour(h.time), color = PanelColors.TextDim, fontSize = 18.sp, maxLines = 1)
                WeatherIcon(h.code, h.isDay, Modifier.size(40.dp).padding(vertical = 2.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(fmt.temp(h.temp), color = PanelColors.Text, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                    if (h.precipProb >= 20) {
                        Text(" ${h.precipProb}%", color = PanelColors.Rain, fontSize = 16.sp, modifier = Modifier.padding(bottom = 3.dp))
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- lower half

@Composable
private fun FocusSection(
    focus: Focus,
    display: DisplayConfig,
    weather: Weather?,
    outlook: Outlook?,
    location: PanelLocation?,
    fmt: Fmt,
    now: Instant,
    modifier: Modifier,
) {
    val remote = display.focusAt(now) != Focus.AUTO
    Column(modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            Text(focus.title.uppercase(), color = PanelColors.TextSecondary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
            Spacer(Modifier.width(14.dp))
            val chip = when {
                !remote -> "AUTO"
                display.focusUntil != null -> "SET REMOTELY · UNTIL ${parseInstant(display.focusUntil)?.let(fmt::time) ?: "?"}"
                else -> "SET REMOTELY"
            }
            Text(
                chip,
                color = if (remote) PanelColors.Warm else PanelColors.TextDim,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (remote) PanelColors.Warm.copy(alpha = 0.15f) else PanelColors.Card)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            if (outlook != null) {
                Text(fmt.headline(outlook, now), color = PanelColors.TextSecondary, fontSize = 20.sp, maxLines = 1)
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                focus == Focus.RADAR && location != null -> RadarPanel(location.coords, display.radarZoom, fmt)
                weather == null -> Text("Waiting for weather data…", color = PanelColors.TextDim, fontSize = 24.sp, modifier = Modifier.align(Alignment.Center))
                focus == Focus.HOURLY -> HourlyPanel(weather, fmt, now)
                focus == Focus.RAIN -> RainPanel(weather, outlook, fmt, now)
                focus == Focus.WIND -> WindPanel(weather, outlook, fmt, now)
                else -> ForecastPanel(weather, fmt, now)
            }
        }
    }
}

// ---------------------------------------------------------------- footer

@Composable
private fun Footer(status: ServerStatus, notes: List<PanelNote>, weather: Weather?, weatherError: String?, fmt: Fmt) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        val (color, label) = when (status) {
            is ServerStatus.Connected -> PanelColors.Ok to null
            is ServerStatus.Connecting -> PanelColors.Warm to "Connecting…"
            ServerStatus.Discovering -> PanelColors.Warm to "Looking for server…"
            is ServerStatus.Offline -> PanelColors.Critical to status.reason
        }
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        if (label != null) {
            Text(label, color = PanelColors.TextDim, fontSize = 16.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
        }
        Spacer(Modifier.width(24.dp))
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
            if (notes.isNotEmpty()) {
                Text(
                    notes.joinToString("        •        ") { it.text },
                    color = PanelColors.Text,
                    fontSize = 20.sp,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                )
            }
        }
        Spacer(Modifier.width(24.dp))
        val updated = when {
            weatherError != null && weather != null -> "Weather offline · last ${fmt.time(weather.fetchedAt)}"
            weather != null -> "Updated ${fmt.time(weather.fetchedAt)}"
            else -> ""
        }
        Text(updated, color = if (weatherError != null) PanelColors.Warm else PanelColors.TextDim, fontSize = 16.sp, maxLines = 1)
    }
}

