package com.example.tabletpanel.data

import android.content.Context
import android.os.Build
import java.util.UUID

/** Local, per-tablet settings. Everything about what the display shows comes from the server. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("panel", Context.MODE_PRIVATE)

    val deviceId: String =
        prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }

    /** e.g. http://192.168.1.20:8787; blank means find the server with mDNS. */
    var serverUrl: String
        get() = prefs.getString("serverUrl", "") ?: ""
        set(v) = prefs.edit().putString("serverUrl", v.trim().trimEnd('/')).apply()

    var apiKey: String
        get() = prefs.getString("apiKey", "") ?: ""
        set(v) = prefs.edit().putString("apiKey", v.trim()).apply()

    var deviceName: String
        get() = prefs.getString("deviceName", null) ?: Build.MODEL
        set(v) = prefs.edit().putString("deviceName", v.trim().ifEmpty { Build.MODEL }).apply()

    /** Last server found by discovery, tried first on the next start. */
    var lastDiscoveredUrl: String?
        get() = prefs.getString("lastDiscoveredUrl", null)
        set(v) = prefs.edit().putString("lastDiscoveredUrl", v).apply()

    /** Last state received from the server, so a reboot without the server still shows the right setup. */
    var cachedState: String?
        get() = prefs.getString("cachedState", null)
        set(v) = prefs.edit().putString("cachedState", v).apply()
}
