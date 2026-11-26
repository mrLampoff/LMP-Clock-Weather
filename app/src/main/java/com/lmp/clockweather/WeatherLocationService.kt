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
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.RemoteViews
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

private data class NotificationHour(
    val time: String,
    val temperature: Int,
    val precipitation: Int,
    val weatherCode: Int
)

class WeatherLocationService : Service() {

    companion object {
        const val ACTION_STOP = "com.lmp.clockweather.STOP_WEATHER_SERVICE"
        private const val CHANNEL_ID = "lmp_weather_channel"
        private const val NOTIFICATION_ID = 1001
        private const val UPDATE_INTERVAL = 30 * 60 * 1000L
    }

    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private val weatherExecutor = Executors.newSingleThreadExecutor()
    private val updateLock = Any()
    private var lastWeatherRequestAt = 0L
    private var lastWeatherLocation: Location? = null
    private var currentCity = "Визначаємо місто…"
    private var currentTemperature = "--°"
    private var currentHigh = "--°"
    private var currentLow = "--°"
    private var hourlyForecast: List<NotificationHour> = emptyList()

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
        try {
            locationManager.removeUpdates(listener)
        } catch (_: Exception) {
        }

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
    val fineGranted =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    val coarseGranted =
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    val backgroundGranted =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
        checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    return (fineGranted || coarseGranted) && backgroundGranted
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
        // Ignore duplicate fixes from GPS/network providers arriving together.
        // A 1 km movement or a 10-minute interval is enough to allow a new fetch.
        val now = System.currentTimeMillis()
        synchronized(updateLock) {
            val previous = lastWeatherLocation
            val distanceMeters = previous?.distanceTo(location) ?: Float.MAX_VALUE
            if (now - lastWeatherRequestAt < 10 * 60 * 1000L && distanceMeters < 1000f) {
                return
            }
            lastWeatherRequestAt = now
            lastWeatherLocation = Location(location)
        }

        weatherExecutor.execute {
            try {
               val city = try {
    // Координати центру Десни
    val desnaLatitude = 50.9311535
    val desnaLongitude = 30.75695

    // Перевіряємо відстань до Десни
    val results = FloatArray(1)

    Location.distanceBetween(
        location.latitude,
        location.longitude,
        desnaLatitude,
        desnaLongitude,
        results
    )

    if (results[0] <= 10000f) {
        "Десна"
    } else {
        val geocoder = Geocoder(this, Locale("uk", "UA"))

        @Suppress("DEPRECATION")
        val address = geocoder.getFromLocation(
            location.latitude,
            location.longitude,
            5
        )?.firstOrNull()

        address?.locality
            ?: address?.subAdminArea
            ?: address?.adminArea
            ?: "Моє місцезнаходження"
    }
} catch (_: Exception) {
    "Моє місцезнаходження"
}

                val endpoint =
                    "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=${location.latitude}" +
                    "&longitude=${location.longitude}" +
                    "&current=temperature_2m,weather_code,wind_speed_10m" +
                    "&hourly=temperature_2m,precipitation_probability,weather_code,wind_speed_10m" +
                    "&daily=temperature_2m_max,temperature_2m_min,weather_code,wind_speed_10m_max" +
                    "&timezone=auto&forecast_days=1"

                val connection =
                    (URL(endpoint).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 12000
                        readTimeout = 12000
                    }

                val json = try {
                    val responseCode = connection.responseCode
                    if (responseCode !in 200..299) {
                        throw java.io.IOException("Open-Meteo HTTP $responseCode")
                    }
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
                val hourly = json.getJSONObject("hourly")
                val hourlyTimes = hourly.getJSONArray("time")
                val hourlyTemps = hourly.getJSONArray("temperature_2m")
                val hourlyRain = hourly.getJSONArray("precipitation_probability")
                val hourlyCodes = hourly.getJSONArray("weather_code")
                val currentHour = current.getString("time").take(13)
                val firstHour = (0 until hourlyTimes.length())
                    .firstOrNull { hourlyTimes.getString(it).startsWith(currentHour) } ?: 0
                val nextHours = (firstHour until minOf(firstHour + 6, hourlyTimes.length())).map { index ->
                    NotificationHour(
                        time = hourlyTimes.getString(index).substringAfter("T"),
                        temperature = hourlyTemps.getDouble(index).toInt(),
                        precipitation = hourlyRain.getInt(index),
                        weatherCode = hourlyCodes.getInt(index)
                    )
                }

                // Check forecast-based weather alerts without changing the service notification.
                WeatherAlertManager.checkAndNotify(this, json, city)

                val temp = current.getDouble("temperature_2m").toInt()
                val high = daily.getJSONArray("temperature_2m_max")
                    .getDouble(0).toInt()
                val low = daily.getJSONArray("temperature_2m_min")
                    .getDouble(0).toInt()

                // Share the same location with the home-screen widget.
                LocationStore.save(this, location.latitude, location.longitude, city)

                handler.post {
                    currentCity = city
                    currentTemperature = "${temp}°"
                    currentHigh = "${high}°"
                    currentLow = "${low}°"
                    hourlyForecast = nextHours
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

        val content = message ?: "$currentTemperature · $currentCity · Мін. $currentLow · Макс. $currentHigh"

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val expandedView = RemoteViews(packageName, R.layout.weather_notification_expanded).apply {
            setTextViewText(R.id.notification_city, "LMP Weather · $currentCity")
            setTextViewText(R.id.notification_current, currentTemperature)
            setTextViewText(R.id.notification_daily, "Мін. $currentLow   ·   Макс. $currentHigh")
            setTextViewText(R.id.notification_forecast_label, "ПОГОДИННИЙ ПРОГНОЗ")

            val timeIds = intArrayOf(
                R.id.forecast_time_0, R.id.forecast_time_1, R.id.forecast_time_2,
                R.id.forecast_time_3, R.id.forecast_time_4, R.id.forecast_time_5
            )
            val rainIds = intArrayOf(
                R.id.forecast_rain_0, R.id.forecast_rain_1, R.id.forecast_rain_2,
                R.id.forecast_rain_3, R.id.forecast_rain_4, R.id.forecast_rain_5
            )
            val iconIds = intArrayOf(
                R.id.forecast_icon_0, R.id.forecast_icon_1, R.id.forecast_icon_2,
                R.id.forecast_icon_3, R.id.forecast_icon_4, R.id.forecast_icon_5
            )
            val tempIds = intArrayOf(
                R.id.forecast_temp_0, R.id.forecast_temp_1, R.id.forecast_temp_2,
                R.id.forecast_temp_3, R.id.forecast_temp_4, R.id.forecast_temp_5
            )

            for (index in 0 until 6) {
                val hour = hourlyForecast.getOrNull(index)
                if (hour != null) {
                    setTextViewText(timeIds[index], hour.time)
                    setTextViewText(rainIds[index], "${hour.precipitation}%")
                    setTextViewText(iconIds[index], weatherEmoji(hour.weatherCode))
                    setTextViewText(tempIds[index], "${hour.temperature}°")
                } else {
                    setTextViewText(timeIds[index], "—")
                    setTextViewText(rainIds[index], "")
                    setTextViewText(iconIds[index], "")
                    setTextViewText(tempIds[index], "")
                }
            }
        }

        return builder
            .setSmallIcon(createTemperatureIcon())
            .setContentTitle("LMP Weather · $currentCity")
            .setContentText(content)
            .setCustomBigContentView(expandedView)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(0, "Зупинити", stopPendingIntent)
            .build()
    }


    private fun weatherEmoji(code: Int): String = when (code) {
        0 -> "☀️"
        1, 2 -> "🌤️"
        3 -> "☁️"
        45, 48 -> "🌫️"
        51, 53, 55, 56, 57 -> "🌦️"
        61, 63, 65, 66, 67, 80, 81, 82 -> "🌧️"
        71, 73, 75, 77, 85, 86 -> "❄️"
        95, 96, 99 -> "⛈️"
        else -> "🌡️"
    }

    private fun createTemperatureIcon(): Icon {
        // Fill as much of Android's fixed status-bar icon area as possible.
        // The OS still controls the final displayed icon bounds.
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val label = currentTemperature

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            style = Paint.Style.FILL
        }

        // Fit the temperature glyph to almost the full square, including
        // wider negative values such as "-12°".
        paint.textSize = 88f
        val maxWidth = 92f
        val measuredWidth = paint.measureText(label).coerceAtLeast(1f)
        if (measuredWidth > maxWidth) {
            paint.textSize *= maxWidth / measuredWidth
        }

        var metrics = paint.fontMetrics
        val glyphHeight = metrics.descent - metrics.ascent
        val maxHeight = 88f
        if (glyphHeight > maxHeight) {
            paint.textSize *= maxHeight / glyphHeight
            metrics = paint.fontMetrics
        }

        val baseline = (bitmap.height - metrics.ascent - metrics.descent) / 2f
        canvas.drawText(label, bitmap.width / 2f, baseline, paint)

        return Icon.createWithBitmap(bitmap)
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
        weatherExecutor.shutdownNow()

        try {
            locationManager.removeUpdates(listener)
        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
