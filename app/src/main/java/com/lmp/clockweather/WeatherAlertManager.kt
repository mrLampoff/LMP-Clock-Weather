package com.lmp.clockweather

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject

/**
 * Forecast-based weather alerts. These are informational estimates from Open-Meteo,
 * not official emergency or government warnings.
 */
object WeatherAlertManager {
    private const val CHANNEL_ID = "lmp_weather_alerts"
    private const val PREFS = "lmp_weather_alert_history"
    private const val QUIET_PERIOD_MS = 6 * 60 * 60 * 1000L

    private data class Alert(
        val key: String,
        val title: String,
        val message: String,
        val id: Int
    )

    fun checkAndNotify(context: Context, root: JSONObject, city: String) {
        try {
            val current = root.getJSONObject("current")
            val daily = root.getJSONObject("daily")
            val hourly = root.getJSONObject("hourly")

            val currentCode = current.optInt("weather_code", -1)
            val currentWind = current.optDouble("wind_speed_10m", 0.0)
            val minTemp = daily.getJSONArray("temperature_2m_min").optDouble(0, 99.0)
            val maxTemp = daily.getJSONArray("temperature_2m_max").optDouble(0, -99.0)
            val maxWind = daily.getJSONArray("wind_speed_10m_max").optDouble(0, currentWind)

            val codes = hourly.getJSONArray("weather_code")
            val rain = hourly.getJSONArray("precipitation_probability")
            val winds = hourly.getJSONArray("wind_speed_10m")
            val maxRainChance = (0 until rain.length()).maxOfOrNull {
                rain.optInt(it, 0)
            } ?: 0
            val hourlyCodes = (0 until codes.length()).map { codes.optInt(it, -1) }
            val hourlyMaxWind = (0 until winds.length()).maxOfOrNull {
                winds.optDouble(it, 0.0)
            } ?: 0.0

            val alerts = mutableListOf<Alert>()

            if (currentCode in 95..99 || hourlyCodes.any { it in 95..99 }) {
                alerts += Alert(
                    "storm", "⛈️ Можлива гроза",
                    "У прогнозі для $city є гроза. За можливості уникай відкритих місць і високих дерев.",
                    2101
                )
            }

            val wind = maxOf(currentWind, maxWind, hourlyMaxWind)
            if (wind >= 50.0) {
                alerts += Alert(
                    "wind", "🌬️ Сильний вітер",
                    "Очікуються пориви/швидкість вітру близько ${wind.toInt()} км/год у районі $city.",
                    2102
                )
            }

            if (minTemp <= 0.0) {
                alerts += Alert(
                    "frost", "❄️ Ризик заморозків",
                    "Мінімальна прогнозована температура для $city: ${minTemp.toInt()}°C.",
                    2103
                )
            }

            if (maxTemp >= 32.0) {
                alerts += Alert(
                    "heat", "🔥 Сильна спека",
                    "Максимальна прогнозована температура для $city: ${maxTemp.toInt()}°C. Подбай про воду та тінь.",
                    2104
                )
            }

            val heavyRainCode = hourlyCodes.any { it in 65..67 || it in 82..82 }
            if (maxRainChance >= 80 && heavyRainCode) {
                alerts += Alert(
                    "rain", "🌧️ Сильні опади",
                    "Ймовірність опадів у прогнозі для $city сягає $maxRainChance%.",
                    2105
                )
            }

            val snowExpected = hourlyCodes.any { it in 71..77 || it in 85..86 }
            if (snowExpected) {
                alerts += Alert(
                    "snow", "🌨️ Очікується сніг",
                    "У погодинному прогнозі для $city очікується сніг або снігопад.",
                    2106
                )
            }

            postNewAlerts(context, alerts)
        } catch (_: Exception) {
            // A malformed or incomplete forecast must not interrupt background weather updates.
        }
    }

    private fun postNewAlerts(context: Context, alerts: List<Alert>) {
        createChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()

        for (alert in alerts) {
            val lastSent = prefs.getLong(alert.key, 0L)
            if (now - lastSent < QUIET_PERIOD_MS) continue

            val openIntent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                alert.id,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }

            val notification = builder
                .setSmallIcon(context.applicationInfo.icon)
                .setContentTitle(alert.title)
                .setContentText(alert.message)
                .setStyle(Notification.BigTextStyle().bigText(alert.message))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_EVENT)
                .build()

            manager.notify(alert.id, notification)
            prefs.edit().putLong(alert.key, now).apply()
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Погодні попередження LMP",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Попередження про небезпечні погодні умови за прогнозом"
                setShowBadge(true)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
