package com.lmp.clockweather

import android.app.AlarmClock
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
                .enqueue(OneTimeWorkRequestBuilder<WidgetWeatherWorker>().build())
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { updateWidget(context, manager, it, "Завантажуємо погоду…", "—°", "🌤️", "Макс. —°  ·  Мін. —°") }
        scheduleWeatherWork(context)
        WorkManager.getInstance(context)
            .enqueue(OneTimeWorkRequestBuilder<WidgetWeatherWorker>().build())
    }

    override fun onEnabled(context: Context) {
        scheduleWeatherWork(context)
        WorkManager.getInstance(context)
            .enqueue(OneTimeWorkRequestBuilder<WidgetWeatherWorker>().build())
    }

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }

    private fun scheduleWeatherWork(context: Context) {
        val periodic = PeriodicWorkRequestBuilder<WidgetWeatherWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )
    }

    companion object {
        private const val ACTION_REFRESH = "com.lmp.clockweather.ACTION_REFRESH_WIDGET"
        private const val UNIQUE_WORK_NAME = "lmp_widget_weather"

        fun updateAll(
            context: Context,
            description: String,
            temperature: String,
            icon: String
        ) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, WeatherWidgetProvider::class.java)
            manager.getAppWidgetIds(component).forEach { id ->
                updateWidget(context, manager, id, description, temperature, icon, null)
            }
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
            val views = RemoteViews(context.packageName, R.layout.weather_widget)
            views.setTextViewText(R.id.widget_weather, description)
            views.setTextViewText(R.id.widget_temperature, temperature)
            views.setTextViewText(R.id.widget_icon, icon)
            if (highLow != null) {
                views.setTextViewText(R.id.widget_high_low, highLow)
            }

            // Clock tap -> Samsung/system alarm list.
            val alarmIntent = Intent(AlarmClock.ACTION_SHOW_ALARMS)
            val alarmPending = activityPendingIntent(context, alarmIntent, id * 10 + 1)
