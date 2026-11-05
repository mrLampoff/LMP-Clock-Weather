
package com.lmp.clockweather

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class WeatherWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action == ACTION_REFRESH) {
            WorkManager.getInstance(context)
                .enqueue(
                    OneTimeWorkRequestBuilder<WidgetWeatherWorker>().build()
                )
        }
    }

    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray
    ) {
        ids.forEach { id ->
            updateWidget(
                context,
                manager,
                id,
                "Завантажуємо погоду…",
                "—°",
                "🌤️",
                "Макс. —°  ·  Мін. —°"
            )
        }

        scheduleWeatherWork(context)

        WorkManager.getInstance(context)
            .enqueue(
                OneTimeWorkRequestBuilder<WidgetWeatherWorker>().build()
            )
    }

    override fun onEnabled(context: Context) {
        scheduleWeatherWork(context)

        WorkManager.getInstance(context)
            .enqueue(
                OneTimeWorkRequestBuilder<WidgetWeatherWorker>().build()
            )
    }

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context)
            .cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    private fun scheduleWeatherWork(context: Context) {
        val periodic =
            PeriodicWorkRequestBuilder<WidgetWeatherWorker>(
                30,
                TimeUnit.MINUTES
            ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )
    }

    companion object {

        private const val ACTION_REFRESH =
            "com.lmp.clockweather.ACTION_REFRESH_WIDGET"

        private const val UNIQUE_WORK_NAME =
            "lmp_widget_weather"

        private const val SAMSUNG_CLOCK_PACKAGE =
            "com.sec.android.app.clockpackage"

        private const val SAMSUNG_CLOCK_ACTIVITY =
            "com.sec.android.app.clockpackage.ClockPackage"

        fun createClockPendingIntent(
            context: Context,
            requestCode: Int
        ): PendingIntent {

            val clockIntent = Intent().apply {
                setClassName(
                    SAMSUNG_CLOCK_PACKAGE,
                    SAMSUNG_CLOCK_ACTIVITY
                )

                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            return PendingIntent.getActivity(
                context,
                requestCode,
                clockIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun createCalendarPendingIntent(
            context: Context,
            requestCode: Int
        ): PendingIntent {

            val calendarIntent = Intent(
                Intent.ACTION_MAIN
            ).addCategory(
                Intent.CATEGORY_APP_CALENDAR
            )

            return PendingIntent.getActivity(
                context,
                requestCode,
                calendarIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun createWeatherPendingIntent(
            context: Context,
            requestCode: Int
        ): PendingIntent {

            val weatherIntent = Intent(
                context,
                MainActivity::class.java
            ).apply {
                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            return PendingIntent.getActivity(
                context,
                requestCode,
                weatherIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun updateWidget(
            context: Context,
            manager: AppWidgetManager,
            id: Int,
            description: String,
            temperature: String,
            icon: String,
            highLow: String?
        ) {

            val views = RemoteViews(
                context.packageName,
                R.layout.weather_widget
            )

            LocationStore.get(context)?.let { saved ->
                views.setTextViewText(
                    R.id.widget_city,
                    saved.city
                )
            }

            views.setTextViewText(
                R.id.widget_weather,
                description
            )

            views.setTextViewText(
                R.id.widget_temperature,
                temperature
            )

            views.setTextViewText(
                R.id.widget_icon,
                icon
            )

            if (highLow != null) {
                views.setTextViewText(
                    R.id.widget_high_low,
                    highLow
                )
            }

            // Clock tap -> Samsung Clock.
            views.setOnClickPendingIntent(
                R.id.widget_clock_area,
                createClockPendingIntent(
                    context,
                    id * 10 + 1
                )
            )

            // Date tap -> Calendar.
            views.setOnClickPendingIntent(
                R.id.widget_date,
                createCalendarPendingIntent(context, id * 10 + 2)
            )

            // Weather tap -> LMP application.
            views.setOnClickPendingIntent(
                R.id.widget_weather_area,
                createWeatherPendingIntent(context, id * 10 + 3)
            )

            // Refresh tap -> Weather update.
            val refreshIntent = Intent(
                context,
                WeatherWidgetProvider::class.java
            ).apply {
                action = ACTION_REFRESH
            }

            val refreshPending = PendingIntent.getBroadcast(
                context,
                id * 10 + 4,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

            views.setOnClickPendingIntent(
                R.id.widget_refresh,
                refreshPending
            )

            manager.updateAppWidget(id, views)
        }
    }
}

class WidgetWeatherWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {

            var connection: HttpURLConnection? = null

            try {

                val savedLocation =
                    LocationStore.get(applicationContext)
                        ?: return@withContext Result.retry()

                val url = URL(
                    "https://api.open-meteo.com/v1/forecast" +
                        "?latitude=${savedLocation.latitude}" +
                        "&longitude=${savedLocation.longitude}" +
                        "&current=temperature_2m,weather_code" +
                        "&daily=temperature_2m_max,temperature_2m_min" +
                        "&timezone=auto&forecast_days=1"
                )

                connection =
                    (url.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 12000
                        readTimeout = 12000
                    }

                val json = JSONObject(
                    connection.inputStream.bufferedReader().use {
                        it.readText()
                    }
                )

                val current = json.getJSONObject("current")
                val daily = json.getJSONObject("daily")

                val temp = current
                    .getDouble("temperature_2m")
                    .toInt()

                val code = current.getInt("weather_code")

                val high = daily
                    .getJSONArray("temperature_2m_max")
                    .getDouble(0)
                    .toInt()

                val low = daily
                    .getJSONArray("temperature_2m_min")
                    .getDouble(0)
                    .toInt()

                val icon = when (code) {
                    0 -> "☀️"
                    1, 2 -> "🌤️"
                    3 -> "☁️"
                    45, 48 -> "🌫️"
                    in 51..67 -> "🌧️"
                    in 71..77 -> "❄️"
                    in 80..82 -> "🌧️"
                    in 95..99 -> "⛈️"
                    else -> "🌤️"
                }

                val description = when (code) {
                    0 -> "Ясно"
                    1 -> "Переважно ясно"
                    2 -> "Мінлива хмарність"
                    3 -> "Хмарно"
                    45, 48 -> "Туман"
                    in 51..57 -> "Мряка"
                    in 61..67 -> "Дощ"
                    in 71..77 -> "Сніг"
                    in 80..82 -> "Зливи"
                    in 95..99 -> "Гроза"
                    else -> "Змінна погода"
                }

                val manager =
                    AppWidgetManager.getInstance(applicationContext)

                val component = ComponentName(
                    applicationContext,
                    WeatherWidgetProvider::class.java
                )

                manager.getAppWidgetIds(component).forEach { id ->

                    updateWidgetFromWorker(
                        id,
                        description,
                        "$temp°",
                        icon,
                        "Макс. $high°  ·  Мін. $low°",
                        savedLocation.city
                    )
                }

                Result.success()

            } catch (_: Exception) {

                Result.retry()

            } finally {
                connection?.disconnect()
            }
        }

    private fun updateWidgetFromWorker(
        appWidgetId: Int,
        description: String,
        temperature: String,
        icon: String,
        highLow: String,
        city: String
    ) {

        val manager =
            AppWidgetManager.getInstance(applicationContext)

        val views = RemoteViews(
            applicationContext.packageName,
            R.layout.weather_widget
        )

        views.setTextViewText(
            R.id.widget_city,
            city
        )

        views.setTextViewText(
            R.id.widget_weather,
            description
        )

        views.setTextViewText(
            R.id.widget_temperature,
            temperature
        )

        views.setTextViewText(
            R.id.widget_icon,
            icon
        )

        views.setTextViewText(
            R.id.widget_high_low,
            highLow
        )

        // Preserve refresh action.
        val refreshIntent = Intent(
            applicationContext,
            WeatherWidgetProvider::class.java
        ).apply {
            action = "com.lmp.clockweather.ACTION_REFRESH_WIDGET"
        }

        val refreshPending = PendingIntent.getBroadcast(
            applicationContext,
            appWidgetId * 10 + 4,
            refreshIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        views.setOnClickPendingIntent(
            R.id.widget_refresh,
            refreshPending
        )

        // Clock tap -> Samsung Clock.
        views.setOnClickPendingIntent(
            R.id.widget_clock_area,
            WeatherWidgetProvider.createClockPendingIntent(
                applicationContext,
                appWidgetId * 10 + 1
             )
        )

        // Date tap -> Calendar.
        val calendarPending = PendingIntent.getActivity(
            applicationContext,
            appWidgetId * 10 + 2,
            Intent(
                Intent.ACTION_MAIN
            ).addCategory(
                Intent.CATEGORY_APP_CALENDAR
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        views.setOnClickPendingIntent(
            R.id.widget_date,
            calendarPending
        )

        // Weather tap -> LMP application.
        val weatherPending = PendingIntent.getActivity(
            applicationContext,
            appWidgetId * 10 + 3,
            Intent(
                applicationContext,
                MainActivity::class.java
            ).apply {
                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        views.setOnClickPendingIntent(
            R.id.widget_weather_area,
            weatherPending
        )

        manager.updateAppWidget(appWidgetId, views)
    }
}
