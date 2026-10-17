package com.babel.platform.game

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.babel.domain.game.ThermalLevel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The platform's thermal status, for [com.babel.domain.game.CapturePace]: the
 * device's own judgement of when it is too warm, rather than a temperature
 * threshold of Babel's that would differ from phone to phone.
 */
@Singleton
class ThermalLevels @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val power by lazy { context.getSystemService(PowerManager::class.java) }

    fun level(): ThermalLevel {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalLevel.NONE
        return when (power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE) {
            PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.NONE
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.MODERATE
            else -> ThermalLevel.SEVERE
        }
    }

    /** The battery's temperature in °C, for the log only; null when unknown. */
    fun batteryCelsius(): Double? {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        return if (tenths == Int.MIN_VALUE) null else tenths / 10.0
    }
}
