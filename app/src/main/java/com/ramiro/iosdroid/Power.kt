package com.ramiro.iosdroid

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager

/** Estado de la batería, para que la app haga lo mínimo cuando no hay cargador. */
object Power {

    /** Por debajo de este nivel (sin cargador) la batería cuenta como baja. */
    const val LOW_LEVEL = 15

    fun charging(context: Context) = context.getSystemService(BatteryManager::class.java)?.isCharging == true

    fun level(context: Context): Int =
        context.getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1

    fun saverOn(context: Context) = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true

    /** Sin cargador y con "Ahorro de energía" activado o poca batería. */
    fun low(context: Context): Boolean {
        if (charging(context)) return false
        val level = level(context)
        return saverOn(context) || level in 0..LOW_LEVEL
    }
}
