
package com.lmp.clockweather

import android.provider.AlarmClock
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
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
import kotlin.math.roundToInt

class WeatherWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action == ACTION_REFRESH) {
            WorkManager.getInstance(context)
                .enqueue(
                    OneTimeWorkRequestBuilder<WidgetWeatherWorker>()
                        .build()
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

        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<WidgetWeatherWorker>().build()
        )
    }

    override fun onEnabled(context: Context) {
        scheduleWeatherWork(context)

        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<WidgetWeatherWorker>().build()
        )
    }

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context)
            .cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    private fun scheduleWeatherWork(context: Context) {
        val periodicWork =
            PeriodicWorkRequestBuilder<WidgetWeatherWorker>(
                30,
                TimeUnit.MINUTES
            ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWork
        )
    }

    companion object {

        private const val ACTION_REFRESH =
            "com.lmp.clockweather.ACTION_REFRESH_WIDGET"

        private const val UNIQUE_WORK_NAME =
            "lmp_widget_weather"

        /**
         * Метод збережений для сумісності
         * з WeatherLocationService.
         */
        fun updateAll(
            context: Context,
            description: String,
            temperature: String,
            icon: String
        ) {
            val manager = AppWidgetManager.getInstance(context)

            val component = ComponentName(
                context,
                WeatherWidgetProvider::class.java
            )

            val ids = manager.getAppWidgetIds(component)

            ids.forEach { id ->
                updateWidget(
                    context,
                    manager,
                    id,
                    description,
                    temperature,
                    icon,
                    null
                )
            }
        }

        private fun activityPendingIntent(
            context: Context,
            intent: Intent,
            requestCode: Int
        ): PendingIntent {
            return PendingIntent.getActivity(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun broadcastPendingIntent(
            context: Context,
            requestCode: Int
        ): PendingIntent {
            val intent = Intent(
                context,
                WeatherWidgetProvider::class.java
            ).apply {
                action = ACTION_REFRESH
            }

            return PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
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

            // Годинник -> список будильників.
            val alarmIntent = Intent(
                AlarmClock.ACTION_SHOW_ALARMS
            )

            views.setOnClickPendingIntent(
                R.id.widget_clock_area,
                activityPendingIntent(
                    context,
                    alarmIntent,
                    id * 10 + 1
                )
            )

            // Дата -> календар.
            val calendarIntent = Intent(
                Intent.ACTION_MAIN
            ).addCategory(
                Intent.CATEGORY_APP_CALENDAR
            )

            views.setOnClickPendingIntent(
                R.id.widget_date,
                activityPendingIntent(
                    context,
                    calendarIntent,
                    id * 10 + 2
                )
            )

            // Погода -> основний екран застосунку
            // з прогнозом на 7 днів.
            val weatherIntent = Intent(
                context,
                MainActivity::class.java
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            views.setOnClickPendingIntent(
                R.id.widget_weather_area,
                activityPendingIntent(
                    context,
                    weatherIntent,
                    id * 10 + 3
                )
            )

            // Кнопка оновлення погоди.
            views.setOnClickPendingIntent(
                R.id.widget_refresh,
                broadcastPendingIntent(
                    context,
                    id * 10 + 4
                )
            )

            manager.updateAppWidget(id, views)
        }
    }
}

/**
 * Фонове отримання погоди з Open-Meteo.
 */
class WidgetWeatherWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val weather = withContext(Dispatchers.IO) {
                loadWeather()
            }

            WeatherWidgetProvider.updateAll(
                applicationContext,
                weather.description,
                weather.temperature,
                weather.icon
            )

            updateHighLow(weather.highLow)

            Result.success()

        } catch (e: Exception) {
            Result.retry()
        }
    }

    private suspend fun updateHighLow(value: String) {
        withContext(Dispatchers.IO) {
            val manager = AppWidgetManager.getInstance(
                applicationContext
            )

            val component = ComponentName(
                applicationContext,
                WeatherWidgetProvider::class.java
            )

            val ids = manager.getAppWidgetIds(component)

            ids.forEach { id ->
                val views = RemoteViews(
                    applicationContext.packageName,
                    R.layout.weather_widget
                )

                views.setTextViewText(
                    R.id.widget_high_low,
                    value
                )

                manager.updateAppWidget(id, views)
            }
        }
    }

    private fun loadWeather(): WidgetWeather {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=50.4501" +
                "&longitude=30.5234" +
                "&current=temperature_2m,weather_code" +
                "&daily=temperature_2m_max,temperature_2m_min" +
                "&timezone=Europe%2FKyiv"
        )

        val connection = url.openConnection()
            as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            val responseCode = connection.responseCode

            if (responseCode !in 200..299) {
                throw IllegalStateException(
                    "Weather API error: $responseCode"
                )
            }

            val response = connection.inputStream
                .bufferedReader()
                .use { it.readText() }

            val json = JSONObject(response)

            val current = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")

            val temperature = current
                .getDouble("temperature_2m")
                .roundToInt()
                .toString() + "°"

            val weatherCode = current.getInt("weather_code")

            val maximum = daily
                .getJSONArray("temperature_2m_max")
                .getDouble(0)
                .roundToInt()

            val minimum = daily
                .getJSONArray("temperature_2m_min")
                .getDouble(0)
                .roundToInt()

            val highLow =
                "Макс. $maximum°  ·  Мін. $minimum°"

            val weatherInfo = getWeatherInfo(weatherCode)

            return WidgetWeather(
                temperature = temperature,
                description = weatherInfo.first,
                icon = weatherInfo.second,
                highLow = highLow
            )

        } finally {
            connection.disconnect()
        }
    }

    private fun getWeatherInfo(code: Int): Pair<String, String> {
        return when (code) {
            0 -> Pair("Ясно", "☀️")
            1 -> Pair("Переважно ясно", "🌤️")
            2 -> Pair("Мінлива хмарність", "⛅")
            3 -> Pair("Хмарно", "☁️")

            45, 48 -> Pair("Туман", "🌫️")

            51, 53, 55 -> Pair("Мряка", "🌦️")
            56, 57 -> Pair("Крижана мряка", "🌧️")

            61, 63, 65 -> Pair("Дощ", "🌧️")
            66, 67 -> Pair("Крижаний дощ", "🌧️")

            71, 73, 75 -> Pair("Сніг", "❄️")
            77 -> Pair("Снігові зерна", "🌨️")

            80, 81, 82 -> Pair("Зливи", "🌧️")
            85, 86 -> Pair("Снігопад", "🌨️")

            95 -> Pair("Гроза", "⛈️")
            96, 99 -> Pair("Гроза з градом", "⛈️")

            else -> Pair("Погода оновлена", "🌤️")
        }
    }

    private data class WidgetWeather(
        val temperature: String,
        val description: String,
        val icon: String,
        val highLow: String
    )
}
