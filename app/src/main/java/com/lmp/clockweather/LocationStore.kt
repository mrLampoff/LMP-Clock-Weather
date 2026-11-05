package com.lmp.clockweather

import android.content.Context

/** Shared last-known location used by the app, background service and home-screen widget. */
object LocationStore {
    private const val PREFS = "lmp_location_store"
    private const val KEY_LATITUDE = "latitude"
    private const val KEY_LONGITUDE = "longitude"
    private const val KEY_CITY = "city"
    private const val KEY_UPDATED_AT = "updated_at"

    data class SavedLocation(
        val latitude: Double,
        val longitude: Double,
        val city: String,
        val updatedAt: Long
    )

    fun save(context: Context, latitude: Double, longitude: Double, city: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LATITUDE, latitude.toString())
            .putString(KEY_LONGITUDE, longitude.toString())
            .putString(KEY_CITY, city.ifBlank { "Моє місцезнаходження" })
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    fun get(context: Context): SavedLocation? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lat = prefs.getString(KEY_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val lon = prefs.getString(KEY_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return SavedLocation(
            latitude = lat,
            longitude = lon,
            city = prefs.getString(KEY_CITY, "Моє місцезнаходження") ?: "Моє місцезнаходження",
            updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L)
        )
    }
}
