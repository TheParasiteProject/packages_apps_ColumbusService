package org.protonaosp.columbus.gates

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventCallback
import android.hardware.SensorManager
import android.os.Handler
import android.util.Log

/*
   This one is slightly odd as a sensor listener doesn't stay running in the background to allow for asynchronous listening.
   We therefore use a little bit of a hacky way of detecting - attach a listener on a background thread and then immediately block the main thread waiting for the event (which is almost instantaneous)
*/

class PocketDetection(context: Context, val handler: Handler) : Gate(context, handler, 2) {
    companion object {
        private const val TAG: String = "PocketDetection"
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val proximitySensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val proximityMax: Float? = proximitySensor?.maximumRange
    private val sensorListener =
        object : SensorEventCallback() {
            override fun onSensorChanged(event: SensorEvent?) {
                if (proximitySensor == null || proximityMax == null) return

                val value = event?.values?.get(0) ?: return

                handler.post { setBlocking(value < proximityMax) }
            }
        }

    fun startListeningForPocket() {
        proximitySensor?.let { sensor ->
            sensorManager.registerListener(
                sensorListener,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL,
                handler,
            )
        }
    }

    fun stopListeningForPocket() {
        proximitySensor?.let { _ -> sensorManager.unregisterListener(sensorListener) }
        setBlocking(false)
    }

    override fun onActivate() {
        if (proximitySensor == null) {
            Log.d(TAG, "Proximity sensor not available. Pocket detection disabled.")
            return
        }
        startListeningForPocket()
    }

    override fun onDeactivate() {
        if (proximitySensor == null) {
            return
        }
        stopListeningForPocket()
    }
}
