package com.example.tabletpanel.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.max

/** The tablet's ambient light sensor, if it has one. */
class LightSensor(context: Context) {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_LIGHT)

    val available: Boolean get() = sensor != null

    /** Lux readings as they change; never emits on a tablet without a sensor. */
    fun readings(): Flow<Float> {
        val m = manager ?: return emptyFlow()
        val s = sensor ?: return emptyFlow()
        return callbackFlow {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    trySend(event.values[0])
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
            }
            m.registerListener(listener, s, SensorManager.SENSOR_DELAY_NORMAL)
            awaitClose { m.unregisterListener(listener) }
        }.conflate()
    }
}

/**
 * Turns lux readings into "the room is dark". Dims only once it has stayed below the threshold
 * for [dimDelayMs] (someone walking past the sensor shouldn't dim the screen), and brightens as
 * soon as the light comes back well above it. The gap between the two levels stops flicker.
 */
class DarknessDetector(private val dimDelayMs: Long = 60_000) {
    var dark = false
        private set
    private var darkSince: Long? = null

    fun update(lux: Float, threshold: Float, nowMs: Long): Boolean {
        if (dark) {
            if (lux > brightAgain(threshold)) {
                dark = false
                darkSince = null
            }
        } else if (lux < threshold) {
            val since = darkSince ?: nowMs.also { darkSince = it }
            if (nowMs - since >= dimDelayMs) dark = true
        } else {
            darkSince = null
        }
        return dark
    }

    companion object {
        fun brightAgain(threshold: Float) = max(threshold * 3, threshold + 10)
    }
}
