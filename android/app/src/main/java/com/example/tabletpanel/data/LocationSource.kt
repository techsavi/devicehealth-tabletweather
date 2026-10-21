package com.example.tabletpanel.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

data class Coordinates(val lat: Double, val lon: Double)

/**
 * The tablet's own position from the platform LocationManager (no Google Play services needed,
 * so it works on de-Googled and budget tablets). A wall display doesn't move, so a coarse fix
 * refreshed every half hour is plenty.
 */
class LocationSource(private val context: Context) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    fun hasPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    suspend fun current(): Coordinates? {
        if (!hasPermission()) return null
        // Network/fused fixes come fastest indoors; fall back to GPS, then to whatever was cached.
        val providers = manager.getProviders(true).filter { it != LocationManager.PASSIVE_PROVIDER }
            .sortedBy { if (it == LocationManager.GPS_PROVIDER) 1 else 0 }
        var fix: Location? = null
        for (p in providers) {
            fix = withTimeoutOrNull(15_000) { requestOne(p) }
            if (fix != null) break
        }
        fix = fix ?: manager.getProviders(false)
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
        return fix?.let { Coordinates(it.latitude, it.longitude) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestOne(provider: String): Location? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= 30) {
            val signal = CancellationSignal()
            manager.getCurrentLocation(provider, signal, context.mainExecutor) { cont.resume(it) }
            cont.invokeOnCancellation { signal.cancel() }
        } else {
            val listener = android.location.LocationListener { if (cont.isActive) cont.resume(it) }
            @Suppress("DEPRECATION")
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            cont.invokeOnCancellation { manager.removeUpdates(listener) }
        }
    }

    /** Town name for the header, or null when no geocoder is available on the device. */
    suspend fun placeName(at: Coordinates): String? = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext null
        runCatching {
            @Suppress("DEPRECATION") // the blocking call is fine on IO and works on every API level
            val a = Geocoder(context, Locale.getDefault()).getFromLocation(at.lat, at.lon, 1)?.firstOrNull()
            a?.locality ?: a?.subAdminArea ?: a?.adminArea
        }.getOrNull()
    }
}
