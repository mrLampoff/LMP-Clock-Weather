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
