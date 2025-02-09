/*
 * Copyright (C) 2020 The Proton AOSP Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package org.protonaosp.columbus

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import org.protonaosp.columbus.actions.*
import org.protonaosp.columbus.gates.*
import org.protonaosp.columbus.sensors.APSensor
import org.protonaosp.columbus.sensors.CHRESensor
import org.protonaosp.columbus.sensors.ColumbusController
import org.protonaosp.columbus.sensors.ColumbusSensor
import org.protonaosp.columbus.sensors.useApSensor

class ColumbusService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {
    // Services
    private lateinit var vibrator: Vibrator
    private lateinit var prefs: SharedPreferences
    private lateinit var action: Action
    private lateinit var vibDoubleTap: VibrationEffect
    private lateinit var sensor: ColumbusSensor
    private lateinit var controller: ColumbusController
    private lateinit var handler: Handler
    private lateinit var wakelock: PowerManager.WakeLock
    private lateinit var settingsGate: Settings
    private var gates = setOf<Gate>()
    private val binder = Binder()

    // State
    private var screenCallbackRegistered = false

    // Settings
    private var enabled = true
    private var sensitivity = 0.03f
        set(value) {
            field = value
            if (enabled) {
                sendNewSensitivity()
            }
        }

    inner class Binder : android.os.Binder() {
        fun getService(): ColumbusService = this@ColumbusService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibrator = vibratorManager.defaultVibrator
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakelock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
        handler = Handler(Looper.getMainLooper())
        prefs = getDePrefs()

        Log.d(TAG, "Initializing Quick Tap gesture")

        if (useApSensor(this)) {
            Log.d(TAG, "Initializing AP Sensor")
            sensor = APSensor(this, sensitivity, handler)
        } else {
            Log.d(TAG, "Initializing CHRE Sensor")
            sensor = CHRESensor(this, sensitivity, handler)
        }
        controller = ColumbusController(this, sensor, handler)
        controller.setGestureListener(columbusControllerListener)
        settingsGate = Settings(this, handler)
        gates =
            setOf(
                TelephonyActivity(this, handler),
                VrMode(this, handler),
                PocketDetection(this, handler),
                TableDetection(this, handler),
            )

        updateHapticIntensity()
        updateAction()
        updateSensitivity()
        updateEnabled()
        updateScreenCallback()

        // Only register for changes after initial pref updates
        prefs.registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroy() {
        // Cleanup preferences listener
        prefs.unregisterOnSharedPreferenceChangeListener(this)

        // Only unregister if we previously registered
        if (screenCallbackRegistered) {
            unregisterReceiver(screenCallback)
            screenCallbackRegistered = false
        }

        // Cleanup gates
        deactivateGates()
        gates = emptySet()

        // Stop sensor and controller
        controller.stopListening()
        sensor.stopListening()

        // Release wakelock if held
        if (wakelock.isHeld) {
            wakelock.release()
        }

        unregisterScreenCallback()

        // Clear references
        action = DummyAction(this)
        super.onDestroy()
    }

    private fun createAction(key: String): Action {
        return when (key) {
            "screenshot" -> ScreenshotAction(this)
            "assistant" -> AssistantAction(this)
            "media" -> PlayPauseAction(this)
            "notifications" -> NotificationAction(this)
            "overview" -> RecentsAction(this)
            "camera" -> CameraAction(this)
            "power_menu" -> PowerMenuAction(this)
            "mute" -> MuteAction(this)
            "flashlight" -> FlashlightAction(this)
            "screen" -> ScreenAction(this)
            "launch" -> LaunchAction(this)

            else -> DummyAction(this)
        }
    }

    private fun updateSensitivity() {
        val value = prefs.getSensitivity(this)
        sensitivity =
            if (value <= 5) {
                value.toFloat() / 100f
            } else {
                (value - 5).toFloat() * 0.15f
            }
        Log.d(TAG, "Setting sensitivity to $sensitivity")
    }

    private fun updateAction() {
        val key = prefs.getAction(this)
        Log.d(TAG, "Setting action to $key")
        action = createAction(key)

        // For settings
        prefs
            .edit()
            .putBoolean(
                getString(R.string.pref_key_allow_screen_off_action_forced),
                !action.canRunWhenScreenOff(),
            )
            .apply()
    }

    private fun updateEnabled() {
        enabled = prefs.getEnabled(this)
        if (enabled) {
            Log.d(TAG, "Enabling gesture")
            activateGates()
            if (blockingGate()) {
                disableGesture()
            } else {
                enableGesture()
            }
        } else {
            Log.d(TAG, "Disabling gesture")
            deactivateGates()
            disableGesture()
        }
    }

    private fun updateScreenCallback() {
        val allowScreenOff = prefs.getAllowScreenOff(this)
        val shouldListenForScreenEvents = !allowScreenOff || !action.canRunWhenScreenOff()

        if (shouldListenForScreenEvents && !screenCallbackRegistered) {
            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                }
            Log.d(TAG, "Listening to screen on/off events")
            registerReceiver(screenCallback, filter)
            screenCallbackRegistered = true
        } else if (!shouldListenForScreenEvents && screenCallbackRegistered) {
            Log.d(TAG, "Stopped listening to screen on/off events")
            unregisterReceiver(screenCallback)
            screenCallbackRegistered = false
        }
    }

    private fun unregisterScreenCallback() {
        if (screenCallbackRegistered) {
            unregisterReceiver(screenCallback)
            screenCallbackRegistered = false
        }
    }

    private fun updateHapticIntensity() {
        val value = prefs.getHapticIntensity(this)
        vibDoubleTap =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                when (value) {
                    "0" -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                    "1" -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                    "2" -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                    else -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                }
            } else {
                when (value) {
                    "0" -> VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE)
                    "1" -> VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                    "2" -> VibrationEffect.createOneShot(75, VibrationEffect.DEFAULT_AMPLITUDE)
                    else -> VibrationEffect.createOneShot(75, VibrationEffect.DEFAULT_AMPLITUDE)
                }
            }
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        if (key == null) return
        when (key) {
            getString(R.string.pref_key_enabled) -> updateEnabled()
            getString(R.string.pref_key_sensitivity) -> updateSensitivity()
            // Action might change screen callback behavior
            getString(R.string.pref_key_action) -> {
                updateAction()
                updateScreenCallback()
            }
            getString(R.string.pref_key_haptic_intensity) -> updateHapticIntensity()
            getString(R.string.pref_key_allow_screen_off) -> updateScreenCallback()
        }
    }

    private fun enableGesture() {
        controller.startListening()
    }

    private fun disableGesture() {
        controller.stopListening()
    }

    private fun sendNewSensitivity() {
        controller.updateSensitivity(sensitivity)
    }

    private fun onGestureDetected(msg: Int) {
        if (msg != 1) return

        wakelock.acquire(2000L)
        try {
            if (!action.canRun()) return

            vibrator.vibrate(vibDoubleTap, sonicAudioAttr)
            if (settingsGate.isBlocking() && settingsGate.handleGesture()) {
                return
            }
            action.run()
        } finally {
            if (wakelock.isHeld) {
                wakelock.release()
            }
        }
    }

    private val columbusControllerListener =
        object : ColumbusController.GestureListener {
            override fun onGestureDetected(sensor: ColumbusSensor, msg: Int) {
                onGestureDetected(msg)
            }
        }

    private fun activateGates() {
        settingsGate.registerListener(gateListener)
        gates.forEach { it.registerListener(gateListener) }
    }

    private fun deactivateGates() {
        settingsGate.unregisterListener(gateListener)
        gates.forEach { it.unregisterListener(gateListener) }
    }

    private fun blockingGate(): Boolean {
        return gates.any { it.isBlocking() }
    }

    private val gateListener =
        object : Gate.Listener {
            override fun onGateChanged(gate: Gate) {
                if (enabled) {
                    updateEnabled()
                }
            }
        }

    private val screenCallback =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                if (enabled) {
                    when (intent.action) {
                        Intent.ACTION_SCREEN_ON -> {
                            Log.d(TAG, "Enabling gesture due to screen on")
                            updateEnabled()
                        }
                        // Disable gesture entirely to save power
                        Intent.ACTION_SCREEN_OFF -> {
                            Log.d(TAG, "Disabling gesture due to screen off")
                            deactivateGates()
                            disableGesture()
                        }
                    }
                }
            }
        }

    companion object {
        // Vibration effects from HapticFeedbackConstants
        // Duplicated because we can't use performHapticFeedback in a background service
        private val sonicAudioAttr: AudioAttributes =
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_ALARM)
                .build()
    }
}
