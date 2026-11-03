
package com.lmp.clockweather

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class WeatherLocationService : Service() {

    companion object {
        const val ACTION_STOP = "com.lmp.clockweather.STOP_WEATHER_SERVICE"
        private const val CHANNEL_ID = "lmp_weather_channel"
        private const val NOTIFICATION_ID = 1001
        private const val UPDATE_INTERVAL = 30 * 60 * 1000L
    }

    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var currentCity = "Визначаємо місто…"
    private var currentTemperature = "--°"
    private var currentHigh = "--°"
    private var currentLow = "--°"

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            updateWeather(location)
        }
    }

    private val periodicUpdate = object : Runnable {
        override fun run() {
            getLastLocation()
            handler.postDelayed(this, UPDATE_INTERVAL)
        }
    }

    override fun onCreate() {
        super.onCreate()
        locationManager =
            getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTracking()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        startTracking()
        return START_STICKY
    }

    private fun startTracking() {
        if (!hasLocationPermission()) {
            updateNotification("Немає дозволу на геолокацію")
            stopSelf()
            return
        }

        try {
            val providers = locationManager.getProviders(true)

            for (provider in providers) {
                try {
                    locationManager.requestLocationUpdates(
                        provider,
                        15 * 60 * 1000L,
                        300f,
                        listener,
                        Looper.getMainLooper()
                    )
                } catch (_: SecurityException) {
                }
            }

            getLastLocation()
            handler.removeCallbacks(periodicUpdate)
            handler.postDelayed(periodicUpdate, UPDATE_INTERVAL)

        } catch (_: Exception) {
            updateNotification("Не вдалося запустити геолокацію")
        }
    }

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun getLastLocation() {
        if (!hasLocationPermission()) return

        try {
            val providers = locationManager.getProviders(true)
            val location = providers.mapNotNull { provider ->
                try {
                    locationManager.getLastKnownLocation(provider)
                } catch (_: Exception) {
                    null
                }
            }.maxByOrNull { it.time }

            if (location != null) {
                updateWeather(location)
            }
        } catch (_: Exception) {
        }
    }

    private fun updateWeather(location: Location) {
        thread {
            try {
                val city = try {
                    val geocoder = Geocoder(this, Locale("uk", "UA"))
                    @Suppress("DEPRECATION")
                    val address = geocoder.getFromLocation(
                        location.latitude,
                        location.longitude,
                        1
                    )?.firstOrNull()

                    address?.locality
                        ?: address?.subAdminArea
                        ?: address?.adminArea
                        ?: "Моє місцезнаходження"
                } catch (_: Exception) {
                    "Моє місцезнаходження"
                }

                val endpoint =
                    "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=${location.latitude}" +
                    "&longitude=${location.longitude}" +
                    "&current=temperature_2m" +
                    "&daily=temperature_2m_max,temperature_2m_min" +
                    "&timezone=auto&forecast_days=1"

                val connection =
                    (URL(endpoint).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 12000
                        readTimeout = 12000
                    }

                val json = try {
                    JSONObject(
                        connection.inputStream.bufferedReader().use {
                            it.readText()
                        }
                    )
                } finally {
                    connection.disconnect()
                }

                val current = json.getJSONObject("current")
                val daily = json.getJSONObject("daily")

                val temp = current.getDouble("temperature_2m").toInt()
                val high = daily.getJSONArray("temperature_2m_max")
                    .getDouble(0).toInt()
                val low = daily.getJSONArray("temperature_2m_min")
                    .getDouble(0).toInt()

                handler.post {
                    currentCity = city
                    currentTemperature = "${if (temp > 0) "+" else ""}${temp}°"
                    currentHigh = "${if (high > 0) "+" else ""}${high}°"
                    currentLow = "${if (low > 0) "+" else ""}${low}°"
                    updateNotification()
                }

            } catch (_: Exception) {
                handler.post {
                    updateNotification("Не вдалося оновити погоду")
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Фонова погода LMP",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Поточна температура та місцезнаходження"
                setShowBadge(false)
            }

            val manager =
                getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String? = null): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WeatherLocationService::class.java).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val content = message ?: "$currentTemperature · Мін. $currentLow · Макс. $currentHigh"

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle("LMP Weather · $currentCity")
            .setContentText(content)
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "$currentCity\n" +
                    "Зараз: $currentTemperature\n" +
                    "Мінімум: $currentLow · Максимум: $currentHigh\n" +
                    "Фонове оновлення активне"
                )
            )
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(0, "Зупинити", stopPendingIntent)
            .build()
    }

    private fun updateNotification(message: String? = null) {
        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun stopTracking() {
        handler.removeCallbacks(periodicUpdate)

        try {
            locationManager.removeUpdates(listener)
        } catch (_: Exception) {
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(periodicUpdate)

        try {
            locationManager.removeUpdates(listener)
        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
