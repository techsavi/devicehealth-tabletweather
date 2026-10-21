package com.example.tabletpanel.ui

import android.annotation.SuppressLint
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.tabletpanel.data.Coordinates
import com.example.tabletpanel.weather.Fmt
import java.util.Locale

/** Animated radar loop: Leaflet + RainViewer tiles in a WebView (assets/radar.html). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RadarPanel(coords: Coordinates, zoom: Int, fmt: Fmt) {
    val context = LocalContext.current
    val template = remember { context.assets.open("radar.html").bufferedReader().use { it.readText() } }
    // Rebuild the page when the view changes; the config is baked in so there's no load-order race.
    key(coords, zoom, fmt.clock24h) {
        val config = String.format(
            Locale.US,
            "window.CONFIG = { lat: %.5f, lon: %.5f, zoom: %d, clock24h: %b };",
            coords.lat, coords.lon, zoom, fmt.clock24h,
        )
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    // WRAP_CONTENT (the default) gives the page a zero-height viewport, so the map never draws.
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    setBackgroundColor(0xFF0B1220.toInt())
                    webViewClient = WebViewClient()
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                            Log.d("RadarPanel", "${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                            return true
                        }
                    }
                    loadDataWithBaseURL("https://panel.local/radar.html", template.replace("/*CONFIG*/", config), "text/html", "utf-8", null)
                }
            },
            onRelease = { it.destroy() },
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)),
        )
    }
}
