package com.aiassistant

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * HapticManager: Provides meaningful vibration patterns for key assistant events.
 *
 * Three distinct patterns:
 *  - vibrateCommandDetected : short double-pulse — user knows voice was heard
 *  - vibrateTaskComplete    : long success buzz — task finished
 *  - vibrateError           : three short bursts — something went wrong
 */
class HapticManager(private val context: Context) {

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Called when a valid voice command is detected. Double-tap pattern. */
    fun vibrateCommandDetected() = vibrate(
        longArrayOf(0, 80, 80, 80),   // delay, on, off, on
        intArrayOf(0, 180, 0, 180)
    )

    /** Called after all task steps are completed successfully. */
    fun vibrateTaskComplete() = vibrate(
        longArrayOf(0, 200),
        intArrayOf(0, 255)
    )

    /** Called when a command could not be understood or an error occurred. */
    fun vibrateError() = vibrate(
        longArrayOf(0, 100, 100, 100, 100, 100),
        intArrayOf(0, 255, 0, 255, 0, 255)
    )

    // -------------------------------------------------------------------------
    // Internal helper
    // -------------------------------------------------------------------------
    private fun vibrate(timings: LongArray, amplitudes: IntArray) {
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(timings, -1)
        }
    }
}
