package com.example.tabletpanel.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tabletpanel.BuildConfig
import com.example.tabletpanel.data.PanelAlert
import com.example.tabletpanel.data.ServerStatus
import com.example.tabletpanel.data.Settings
import com.example.tabletpanel.data.parseInstant
import com.example.tabletpanel.weather.Fmt

private fun severityColor(s: String) = when (s) {
    "critical" -> PanelColors.Critical
    "warning" -> PanelColors.Warning
    else -> PanelColors.Info
}

/** Critical alerts take over the screen; others stack as banners across the top. */
@Composable
fun AlertOverlay(alerts: List<PanelAlert>, fmt: Fmt, onDismiss: (PanelAlert) -> Unit) {
    val critical = alerts.lastOrNull { it.critical }
    val banners = alerts.filterNot { it.critical }.takeLast(3).reversed()

    Box(Modifier.fillMaxSize()) {
        if (banners.isNotEmpty()) {
            Column(
                Modifier.align(Alignment.TopCenter).padding(top = 20.dp).widthIn(max = 980.dp).fillMaxWidth(0.82f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                banners.forEach { Banner(it, fmt, onDismiss) }
            }
        }
        if (critical != null) CriticalAlert(critical, onDismiss)
    }
}

@Composable
private fun Banner(a: PanelAlert, fmt: Fmt, onDismiss: (PanelAlert) -> Unit) {
    val color = severityColor(a.severity)
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .shadow(16.dp, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF101826))
            .clickable(enabled = a.dismissible) { onDismiss(a) },
    ) {
        Box(Modifier.width(12.dp).fillMaxHeight().background(color))
        Column(Modifier.weight(1f).padding(horizontal = 24.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    a.title ?: a.message ?: "",
                    color = PanelColors.Text,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                a.createdAt?.let(::parseInstant)?.let { Text(fmt.time(it), color = PanelColors.TextDim, fontSize = 18.sp) }
            }
            if (a.title != null && a.message != null) {
                Text(a.message, color = PanelColors.TextSecondary, fontSize = 24.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (a.dismissible) Text("Tap to dismiss", color = PanelColors.TextDim, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun CriticalAlert(a: PanelAlert, onDismiss: (PanelAlert) -> Unit) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.82f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF7F1010).copy(alpha = pulse))
            .clickable(enabled = a.dismissible) { onDismiss(a) }
            .padding(64.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("⚠", color = Color.White, fontSize = 110.sp)
            Text(
                a.title ?: a.message ?: "Alert",
                color = Color.White,
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineHeight = 80.sp,
            )
            if (a.title != null && a.message != null) {
                Spacer(Modifier.height(16.dp))
                Text(a.message, color = Color.White.copy(alpha = 0.9f), fontSize = 40.sp, textAlign = TextAlign.Center, lineHeight = 48.sp)
            }
            if (a.dismissible) {
                Spacer(Modifier.height(40.dp))
                Text("Tap anywhere to dismiss", color = Color.White.copy(alpha = 0.7f), fontSize = 22.sp)
            }
        }
    }
}

/** Shown on "identify" from the web UI, to tell which tablet is which. */
@Composable
fun IdentifyOverlay(name: String, status: ServerStatus) {
    Box(Modifier.fillMaxSize().background(Color(0xE6000000)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("This display is", color = PanelColors.TextSecondary, fontSize = 32.sp)
            Text(name, color = PanelColors.Accent, fontSize = 96.sp, fontWeight = FontWeight.Bold)
            if (status is ServerStatus.Connected) Text("Connected to ${status.url}", color = PanelColors.TextDim, fontSize = 24.sp)
        }
    }
}

@Composable
fun SettingsDialog(
    settings: Settings,
    status: ServerStatus,
    locationProblem: String?,
    onSave: (url: String, apiKey: String, name: String) -> Unit,
    onRequestLocation: () -> Unit,
    onExit: () -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(settings.serverUrl) }
    var key by remember { mutableStateOf(settings.apiKey) }
    var name by remember { mutableStateOf(settings.deviceName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Display settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.widthIn(min = 520.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Display name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    url,
                    { url = it },
                    label = { Text("Server address") },
                    placeholder = { Text("Blank = find automatically, or e.g. 192.168.1.20:8787") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(key, { key = it }, label = { Text("API key (if the server uses one)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                val statusText = when (status) {
                    is ServerStatus.Connected -> "Connected to ${status.url}"
                    is ServerStatus.Connecting -> "Connecting to ${status.url}…"
                    ServerStatus.Discovering -> "Searching the network for the server…"
                    is ServerStatus.Offline -> status.reason
                }
                Text(statusText, color = PanelColors.TextSecondary)
                if (locationProblem != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(locationProblem, color = PanelColors.Warm, modifier = Modifier.weight(1f))
                        TextButton(onClick = onRequestLocation) { Text("Allow location") }
                    }
                }
                Text("Device ${settings.deviceId.take(8)} · app ${BuildConfig.VERSION_NAME}", color = PanelColors.TextDim, fontSize = 13.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(url, key, name) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = onExit) { Text("Exit app", color = PanelColors.Critical) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
