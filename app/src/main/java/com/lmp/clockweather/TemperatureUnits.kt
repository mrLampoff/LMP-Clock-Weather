package com.lmp.clockweather

import android.content.Context

/** Shared temperature preference and conversion for app, widget and notification. */
object TemperatureUnits {
    private const val PREFS = "lmp_settings"
    private const val KEY_USE_CELSIUS = "use_celsius"

    fun isCelsius(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_USE_CELSIUS, true)

    fun format(celsius: Int, useCelsius: Boolean): Int =
        if (useCelsius) celsius else (celsius * 9.0 / 5.0 + 32.0).toInt()

    fun format(context: Context, celsius: Int): Int = format(celsius, isCelsius(context))

    fun symbol(context: Context): String = if (isCelsius(context)) "°C" else "°F"
}
