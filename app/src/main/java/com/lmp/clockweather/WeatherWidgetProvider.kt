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
import java.util.Locale
import java.util.concurrent.TimeUnit

class WeatherWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(context, manager, it, "Завантажуємо погоду…", "—", "🌤️") }
        val periodic = PeriodicWorkRequestBuilder<WidgetWeatherWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "lmp_widget_weather", ExistingPeriodicWorkPolicy.KEEP, periodic
        )
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<WidgetWeatherWorker>().build())
    }

    override fun onEnabled(context: Context) {
        val periodic = PeriodicWorkRequestBuilder<WidgetWeatherWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "lmp_widget_weather", ExistingPeriodicWorkPolicy.KEEP, periodic
        )
        WorkManager.getInstance(context).enqueue(OneTimeWorkRequestBuilder<WidgetWeatherWorker>().build())
    }

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("lmp_widget_weather")
    }

    companion object {
        fun updateAll(context: Context, description: String, temperature: String, icon: String) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, WeatherWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach {
                updateWidget(context, manager, it, description, temperature, icon)
            }
        }

        private fun updateWidget(context: Context, manager: AppWidgetManager, id: Int,
                                 description: String, temperature: String, icon: String) {
            val views = RemoteViews(context.packageName, R.layout.weather_widget)
            views.setTextViewText(R.id.widget_weather, description)
            views.setTextViewText(R.id.widget_temperature, temperature)
            views.setTextViewText(R.id.widget_icon, icon)
            val open = Intent(context, MainActivity::class.java)
            val pending = PendingIntent.getActivity(
                context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pending)
            manager.updateAppWidget(id, views)
        }
    }
}

class WidgetWeatherWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val url = URL(
                "https://api.open-meteo.com/v1/forecast?latitude=50.4501&longitude=30.5234&current=temperature_2m,weather_code&timezone=auto"
            )
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 12000
            }
            val json = try {
                JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            } finally {
                connection.disconnect()
            }
            val current = json.getJSONObject("current")
            val temp = current.getDouble("temperature_2m").toInt()
            val code = current.getInt("weather_code")
            val icon = when (code) {
                0 -> "☀️"; 1, 2 -> "🌤️"; 3 -> "☁️"; 45, 48 -> "🌫️"
                in 51..67 -> "🌧️"; in 71..77 -> "❄️"; in 80..82 -> "🌧️"
                in 95..99 -> "⛈️"; else -> "🌤️"
            }
            val description = when (code) {
                0 -> "Ясно"; 1 -> "Переважно ясно"; 2 -> "Мінлива хмарність"; 3 -> "Хмарно"
                45, 48 -> "Туман"; in 51..57 -> "Мряка"; in 61..67 -> "Дощ"
                in 71..77 -> "Сніг"; in 80..82 -> "Зливи"; in 95..99 -> "Гроза"
                else -> "Змінна погода"
            }
            WeatherWidgetProvider.updateAll(applicationContext, description, "$temp°", icon)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
