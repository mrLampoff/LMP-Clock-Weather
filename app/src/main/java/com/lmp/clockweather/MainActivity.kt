package com.lmp.clockweather

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import android.location.LocationManager
import android.location.Geocoder
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import android.os.Build
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val cream = Color(0xFFFFF8EF)
private val mutedWhite = Color(0xD9FFFFFF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(6, 59, 70)
        window.navigationBarColor = android.graphics.Color.rgb(18, 61, 80)
        setContent { LmpWeatherApp() }
    }
}

data class WeatherNow(
    val temperature: Int,
    val feelsLike: Int,
    val humidity: Int,
    val wind: Int,
    val code: Int,
    val high: Int,
    val low: Int,
    val pressureHpa: Double,
    val windDirection: Int,
    val city: String = "Київ",
    val visibilityMeters: Double = 10000.0,
    val dewPoint: Double = 0.0,
    val cloudCover: Int = 0
)

data class DayForecast(val day: String, val code: Int, val high: Int, val low: Int)

data class HourForecast(
    val time: String, val temperature: Int, val code: Int, val precipitation: Int, val wind: Int,
    val precipitationMm: Double = 0.0, val rainMm: Double = 0.0, val snowfallCm: Double = 0.0,
    val cloudCover: Int = 0, val uvIndex: Double = 0.0
)

data class AirQuality(
    val europeanAqi: Int,
    val pm25: Double,
    val pm10: Double,
    val updatedAt: String
)

data class SolarUv(
    val sunrise: String,
    val sunset: String,
    val daylight: String,
    val currentUv: Double,
    val maxUv: Double
)

data class DailyOutlook(
    val high: Int,
    val low: Int,
    val weatherCode: Int,
    val rainMm: Double,
    val snowCm: Double,
    val precipitationProbability: Int,
    val maxWind: Int
)

data class WeatherBundle(
    val now: WeatherNow,
    val days: List<DayForecast>,
    val hours: List<HourForecast>,
    val hours48: List<HourForecast>,
    val airQuality: AirQuality?,
    val solarUv: SolarUv,
    val todayOutlook: DailyOutlook,
    val tomorrowOutlook: DailyOutlook,
    val dailyRainMm: Double = 0.0,
    val dailySnowfallCm: Double = 0.0
)

@Composable
fun LmpWeatherApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var notificationChecked by remember {
        mutableStateOf(false)
    }

    var weather by remember {
        mutableStateOf<WeatherBundle?>(null)
    }

    var loading by remember {
        mutableStateOf(false)
    }

    var error by remember {
        mutableStateOf("")
    }

    var showSettings by remember {
        mutableStateOf(false)
    }

    var useCelsius by remember {
        mutableStateOf(true)
    }

    var locationName by remember {
        mutableStateOf("Київ")
    }

    // Завантаження погоди
    fun load(
        lat: Double = 50.4501,
        lon: Double = 30.5234,
        city: String = "Київ"
    ) {
        scope.launch {
            loading = true
            error = ""

            try {
                val result = withContext(Dispatchers.IO) {
                    fetchWeather(lat, lon, city)
                }

                weather = result
                locationName = city

            } catch (e: Exception) {
                error = "Не вдалося отримати погоду. Перевір підключення до інтернету."
            } finally {
                loading = false
            }
        }
    }

    // Запуск фонового сервісу та завантаження погоди
    fun startServiceAndLoad() {
        val serviceIntent = Intent(
            context,
            WeatherLocationService::class.java
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            error = "Не вдалося запустити фонове оновлення погоди."
        }

          getFreshLocation(context) { freshLocation ->

            val loc = freshLocation ?: getLastKnownLocation(context)

            if (loc != null) {
                scope.launch {
                    val city = withContext(Dispatchers.IO) {
                        getCityName(
                            context,
                            loc.latitude,
                            loc.longitude
                        )
                    }

                    LocationStore.save(
                        context,
                        loc.latitude,
                        loc.longitude,
                        city
                    )

                    load(
                        loc.latitude,
                        loc.longitude,
                        city
                    )
                }
            } else {
                load()
            }
        }
    }

    // Дозвіл на доступ до місцезнаходження у фоні через налаштування
    val backgroundSettingsLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            val backgroundGranted =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                context.checkSelfPermission(
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

            if (backgroundGranted) {
                startServiceAndLoad()
            } else {
                load()
            }
        }

    // Запит фонового доступу для Android 10
    val backgroundPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                startServiceAndLoad()
            } else {
                load()
            }
        }

    // Запит дозволу на звичайне місцезнаходження
    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->

            val locationGranted =
                grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (locationGranted) {

                val backgroundGranted =
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                    context.checkSelfPermission(
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                if (!backgroundGranted) {

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        )

                        backgroundSettingsLauncher.launch(intent)

                    } else {

                        backgroundPermissionLauncher.launch(
                            Manifest.permission.ACCESS_BACKGROUND_LOCATION
                        )
                    }

                } else {
                    startServiceAndLoad()
                }

            } else {
                load()
            }
        }

    // Перевірка всіх дозволів і запуск потрібного сценарію
    fun ensureBackgroundAndStart() {

        val fineGranted =
            context.checkSelfPermission(
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        val coarseGranted =
            context.checkSelfPermission(
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (!fineGranted && !coarseGranted) {

            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )

            return
        }

        val backgroundGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            context.checkSelfPermission(
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

        if (!backgroundGranted) {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                )

                backgroundSettingsLauncher.launch(intent)

            } else {

                backgroundPermissionLauncher.launch(
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                )
            }

            return
        }

        startServiceAndLoad()
    }

    // Запит дозволу на сповіщення
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            notificationChecked = true
        }

    // Перший запуск: перевіряємо дозвіл на сповіщення
    LaunchedEffect(Unit) {

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )

        } else {
            notificationChecked = true
        }
    }

    // Після перевірки сповіщень запускаємо перевірку геолокації
    LaunchedEffect(notificationChecked) {

        if (notificationChecked) {
            ensureBackgroundAndStart()
        }
    }

    // Відображення екранів
    if (showSettings) {

        SettingsScreen(
            useCelsius = useCelsius,
            onCelsiusChange = {
                useCelsius = it
            },
            onBack = {
                showSettings = false
            }
        )

    } else {

        MainWeatherScreen(
            weather = weather,
            loading = loading,
            error = error,
            locationName = locationName,
            useCelsius = useCelsius,
            onRefresh = {
                // Refresh using the last detected location instead of silently reverting to Kyiv.
                val saved = LocationStore.get(context)
                if (saved != null) {
                    load(saved.latitude, saved.longitude, saved.city)
                } else {
                    startServiceAndLoad()
                }
            },
            onSettings = {
                showSettings = true
            },
            onLocation = {
                ensureBackgroundAndStart()
            }
        )
    }
}

private fun Modifier.oceanGlass(
    radius: androidx.compose.ui.unit.Dp = 26.dp,
    fillAlpha: Float = 0.20f
): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .clip(shape)
        .background(
            Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to Color(0xFFE9FFFC).copy(alpha = fillAlpha + 0.10f),
                    0.16f to Color(0xFFB9FFF6).copy(alpha = fillAlpha + 0.035f),
                    0.48f to Color(0xFF63D9D1).copy(alpha = fillAlpha * 0.58f),
                    1.0f to Color(0xFF073D52).copy(alpha = fillAlpha * 0.52f)
                )
            )
        )
        .border(
            width = 1.25.dp,
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.0f to Color(0xFFE9FFFC).copy(alpha = 0.70f),
                    0.38f to Color(0xFF8EF4E8).copy(alpha = 0.42f),
                    1.0f to Color(0xFF6BDDD4).copy(alpha = 0.25f)
                )
            ),
            shape = shape
        )
}

@Composable
fun MainWeatherScreen(
    weather: WeatherBundle?, loading: Boolean, error: String, locationName: String,
    useCelsius: Boolean, onRefresh: () -> Unit, onSettings: () -> Unit, onLocation: () -> Unit
) {
    var clockNow by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { clockNow = Date(); delay(1000) } }
    val background = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF063B4A),
            Color(0xFF087F83),
            Color(0xFF079B91)
        )
    )
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(background)
            .paint(
                painter = painterResource(R.drawable.mountain_ocean_background),
                contentScale = ContentScale.Crop
            )
            .navigationBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Оновити", tint = cream) }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Налаштування", tint = cream) }
            }
            Spacer(Modifier.height(6.dp))
            Text(SimpleDateFormat("HH:mm", Locale("uk", "UA")).format(clockNow), color = cream, fontSize = 84.sp, fontWeight = FontWeight.Thin, letterSpacing = (-3.5).sp, textAlign = TextAlign.Center, lineHeight = 90.sp)
            Text(SimpleDateFormat("EEEE, d MMMM", Locale("uk", "UA")).format(clockNow).replaceFirstChar { it.uppercase() }, color = mutedWhite, fontSize = 20.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, null, tint = cream, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text(locationName, color = cream, fontSize = 18.sp)
            }
            Spacer(Modifier.height(20.dp))
            if (weather != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Image(painter = painterResource(weatherIconRes(weather.now.code)), contentDescription = weatherDescription(weather.now.code), modifier = Modifier.size(82.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("${formatTemp(weather.now.temperature, useCelsius)}°", color = cream, fontSize = 76.sp, fontWeight = FontWeight.Thin, lineHeight = 80.sp)
                        Text(weatherDescription(weather.now.code), color = mutedWhite, fontSize = 17.sp)
                    }
                }
                Text("Відчувається як ${formatTemp(weather.now.feelsLike, useCelsius)}°", color = mutedWhite, fontSize = 16.sp)
                Spacer(Modifier.height(10.dp))
                Text("Макс. ${formatTemp(weather.now.high, useCelsius)}°   ·   Мін. ${formatTemp(weather.now.low, useCelsius)}°", color = cream, fontSize = 18.sp)
                Spacer(Modifier.height(22.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    WeatherMetric("Вологість", "${weather.now.humidity}%", R.drawable.weather_humidity, Modifier.weight(1f))
                    WeatherMetric("Вітер", "${weather.now.wind} км/год", R.drawable.weather_wind, Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PressureCard(weather.now.pressureHpa, Modifier.weight(1f))
                    WindDirectionCard(weather.now.windDirection, Modifier.weight(1f))
                }
                Spacer(Modifier.height(14.dp))
                VisibilityCard(
                    visibilityMeters = weather.now.visibilityMeters,
                    weatherCode = weather.now.code,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(14.dp))
                AtmosphereCard(
                    humidity = weather.now.humidity,
                    dewPoint = weather.now.dewPoint,
                    cloudCover = weather.now.cloudCover,
                    useCelsius = useCelsius,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(18.dp))
                weather.airQuality?.let { air ->
                    AirQualityCard(air, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(18.dp))
                }
                SolarUvCard(weather.solarUv, Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                PrecipitationCard(
                    hours = weather.hours,
                    dailyRainMm = weather.dailyRainMm,
                    dailySnowfallCm = weather.dailySnowfallCm,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(24.dp))
                if (weather.hours.size >= 2) {
                    Spacer(Modifier.height(2.dp))
                    TemperatureTrendCard(
                        hours = weather.hours,
                        useCelsius = useCelsius,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(24.dp))
                }
                Text("ПОГОДИННИЙ ПРОГНОЗ", color = mutedWhite, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(end = 4.dp)) {
                    items(weather.hours.size) { index ->
                        val hour = weather.hours[index]
                        Column(
                            modifier = Modifier.width(76.dp).oceanGlass(18.dp, 0.12f).padding(vertical = 12.dp, horizontal = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(hour.time, color = mutedWhite, fontSize = 13.sp)
                            Spacer(Modifier.height(8.dp))
                            Image(painter = painterResource(weatherIconRes(hour.code)), contentDescription = weatherDescription(hour.code), modifier = Modifier.size(30.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("${formatTemp(hour.temperature, useCelsius)}°", color = cream, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(5.dp))
                            Text("💧 ${hour.precipitation}%", color = mutedWhite, fontSize = 11.sp)
                            Text("${hour.wind} км/г", color = mutedWhite, fontSize = 10.sp)
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                BestOutdoorTimeCard(
                    hours = weather.hours,
                    useCelsius = useCelsius,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(22.dp))
                Forecast48Card(
                    hours = weather.hours48,
                    useCelsius = useCelsius,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(18.dp))
                TodayTomorrowCard(
                    today = weather.todayOutlook,
                    tomorrow = weather.tomorrowOutlook,
                    useCelsius = useCelsius,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(26.dp))
                Text("ПРОГНОЗ НА 7 ДНІВ", color = mutedWhite, fontSize = 12.sp, letterSpacing = 2.sp, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                weather.days.forEach { day ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(day.day, color = cream, modifier = Modifier.weight(1f), fontSize = 16.sp)
                        Image(painter = painterResource(weatherIconRes(day.code)), contentDescription = weatherDescription(day.code), modifier = Modifier.size(34.dp))
                        Text("${formatTemp(day.low, useCelsius)}°", color = mutedWhite, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
                        Text("${formatTemp(day.high, useCelsius)}°", color = cream, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = .18f))
                }
            } else if (loading) {
                Spacer(Modifier.height(20.dp)); CircularProgressIndicator(color = cream)
                Text("Завантажуємо погоду…", color = cream, modifier = Modifier.padding(12.dp))
            }
            if (error.isNotEmpty()) Text(error, color = cream, textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
            Spacer(Modifier.height(22.dp))
            OutlinedButton(onClick = onLocation, border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .55f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = cream)) {
                Icon(Icons.Default.LocationOn, null); Spacer(Modifier.width(8.dp)); Text("Визначити моє місцезнаходження")
            }
            Spacer(Modifier.height(18.dp))
            Text("Дані: Open-Meteo", color = Color.White.copy(alpha = .55f), fontSize = 11.sp)
            Text("LMP Clock & Weather", color = Color.White.copy(alpha = .55f), fontSize = 11.sp)
        }
    }
}

private data class OutdoorWindow(
    val start: String,
    val end: String,
    val score: Int,
    val reason: String
)

private fun outdoorComfortScore(hour: HourForecast): Int {
    val temperatureScore = when {
        hour.temperature in 18..24 -> 100
        hour.temperature in 15..27 -> 85
        hour.temperature in 10..30 -> 65
        hour.temperature in 5..34 -> 40
        else -> 20
    }
    val windPenalty = when {
        hour.wind <= 10 -> 0
        hour.wind <= 20 -> 8
        hour.wind <= 30 -> 20
        else -> 35
    }
    val rainPenalty = when {
        hour.precipitation >= 70 -> 55
        hour.precipitation >= 40 -> 35
        hour.precipitation >= 20 -> 18
        else -> 0
    }
    val amountPenalty = (hour.precipitationMm * 12.0).toInt().coerceAtMost(35)
    val uvPenalty = when {
        hour.uvIndex >= 8 -> 25
        hour.uvIndex >= 6 -> 18
        hour.uvIndex >= 3 -> 8
        else -> 0
    }
    return (temperatureScore - windPenalty - rainPenalty - amountPenalty - uvPenalty)
        .coerceIn(0, 100)
}

private fun findBestOutdoorWindow(hours: List<HourForecast>): OutdoorWindow? {
    if (hours.isEmpty()) return null
    val windowSize = minOf(2, hours.size)
    val candidates = (0..hours.size - windowSize).map { start ->
        val slice = hours.subList(start, start + windowSize)
        val score = (slice.map { outdoorComfortScore(it) }.average()).toInt()
        Triple(start, slice, score)
    }
    val best = candidates.maxByOrNull { it.third } ?: return null
    val firstHour = best.second.first()
    val lastHour = best.second.last()
    val endHour = (lastHour.time.substringBefore(":").toIntOrNull() ?: 0) + 1
    val end = "%02d:00".format(Locale.US, endHour % 24)
    val reason = when {
        best.second.any { it.precipitation >= 40 || it.precipitationMm >= 0.5 } -> "У цей час можливі опади"
        best.second.any { it.wind > 25 } -> "Врахуй поривчастий вітер"
        best.second.any { it.uvIndex >= 6 } -> "Високий УФ-індекс, подбай про захист"
        best.third >= 80 -> "Комфортні умови для прогулянки"
        else -> "Це найкомфортніший проміжок серед доступних годин"
    }
    return OutdoorWindow(firstHour.time, end, best.third, reason)
}

@Composable
fun BestOutdoorTimeCard(
    hours: List<HourForecast>,
    useCelsius: Boolean,
    modifier: Modifier = Modifier
) {
    val best = remember(hours) { findBestOutdoorWindow(hours) }
    Column(
        modifier = modifier
            .oceanGlass(22.dp).padding(16.dp)
    ) {
        Text("КОЛИ КРАЩЕ ВИЙТИ НАДВІР?", color = mutedWhite, fontSize = 12.sp, letterSpacing = 1.3.sp)
        Spacer(Modifier.height(10.dp))
        if (best == null) {
            Text("Поки немає достатньо погодинних даних", color = cream, fontSize = 14.sp)
        } else {
            Text(
                "${best.start} – ${best.end}",
                color = cream,
                fontSize = 25.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(5.dp))
            Text("Комфортність умов: ${best.score}/100", color = cream, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .padding(3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(best.score / 100f)
                        .height(7.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF8BC9A2), Color(0xFFE6D18A))
                            )
                        )
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(best.reason, color = mutedWhite, fontSize = 12.sp)
            Text(
                "Оцінка за температурою, вітром, опадами та УФ-індексом. Це орієнтир, а не гарантія погоди.",
                color = mutedWhite.copy(alpha = 0.75f),
                fontSize = 10.sp
            )
        }
    }
}

@Composable
fun TodayTomorrowCard(
    today: DailyOutlook,
    tomorrow: DailyOutlook,
    useCelsius: Boolean,
    modifier: Modifier = Modifier
) {
    val temperatureDifference = tomorrow.high - today.high
    val rainExpected = tomorrow.rainMm >= 1.0 || tomorrow.precipitationProbability >= 40
    val windy = tomorrow.maxWind >= 30
    val advice = when {
        tomorrow.weatherCode in listOf(95, 96, 99) -> "Завтра можлива гроза. Стеж за оновленнями прогнозу."
        tomorrow.snowCm >= 1.0 -> "Завтра можливий сніг. Обирай тепле взуття та одяг."
        rainExpected -> "Завтра можливий дощ. Парасолька може стати у пригоді."
        windy -> "Завтра буде вітряно. Одягнися з урахуванням вітру."
        tomorrow.high <= 5 -> "Завтра прохолодно. Варто вдягнутися тепліше."
        tomorrow.high >= 27 -> "Завтра тепло. Не забудь про воду та захист від сонця."
        else -> "Завтра умови сприятливі для звичних планів надворі."
    }
    val changeText = when {
        temperatureDifference > 1 -> "Тепліше на $temperatureDifference°"
        temperatureDifference < -1 -> "Холодніше на ${-temperatureDifference}°"
        else -> "Температура майже не зміниться"
    }

    Column(
        modifier = modifier
            .oceanGlass(22.dp).padding(16.dp)
    ) {
        Text("СЬОГОДНІ ТА ЗАВТРА", color = mutedWhite, fontSize = 12.sp, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DailyOutlookColumn("СЬОГОДНІ", today, useCelsius, Modifier.weight(1f))
            DailyOutlookColumn("ЗАВТРА", tomorrow, useCelsius, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Text(changeText, color = cream, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Text("ПОРАДА НА ЗАВТРА", color = mutedWhite, fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        Text(advice, color = cream, fontSize = 13.sp)
    }
}

@Composable
private fun DailyOutlookColumn(
    title: String,
    outlook: DailyOutlook,
    useCelsius: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.15f),
                        Color.White.copy(alpha = 0.055f)
                    )
                )
            )
            .border(0.8.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Text(title, color = mutedWhite, fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(weatherIconRes(outlook.weatherCode)),
                contentDescription = weatherDescription(outlook.weatherCode),
                modifier = Modifier.size(34.dp)
            )
            Spacer(Modifier.width(7.dp))
            Text(
                "${formatTemp(outlook.high, useCelsius)}° / ${formatTemp(outlook.low, useCelsius)}°",
                color = cream,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(7.dp))
        Text("Опади: ${"%.1f".format(Locale.US, outlook.rainMm)} мм", color = mutedWhite, fontSize = 11.sp)
        Text("Імовірність: ${outlook.precipitationProbability}%", color = mutedWhite, fontSize = 11.sp)
        Text("Вітер до ${outlook.maxWind} км/г", color = mutedWhite, fontSize = 11.sp)
    }
}

@Composable
fun Forecast48Card(
    hours: List<HourForecast>,
    useCelsius: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .oceanGlass(22.dp).padding(16.dp)
    ) {
        Text("ДЕТАЛЬНИЙ ПРОГНОЗ НА 48 ГОДИН", color = mutedWhite, fontSize = 12.sp, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(12.dp))
        Text("ПЕРШІ 24 ГОДИНИ", color = cream, fontSize = 11.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        Forecast48Row(hours.take(24), useCelsius)
        if (hours.size > 24) {
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Color.White.copy(alpha = 0.18f))
            Spacer(Modifier.height(14.dp))
            Text("НАСТУПНІ 24 ГОДИНИ", color = cream, fontSize = 11.sp, letterSpacing = 1.sp)
            Spacer(Modifier.height(8.dp))
            Forecast48Row(hours.drop(24).take(24), useCelsius)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Для кожної години показано температуру, опади, вітер і хмарність",
            color = mutedWhite,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun Forecast48Row(hours: List<HourForecast>, useCelsius: Boolean) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(hours.size) { index ->
            val hour = hours[index]
            Column(
                modifier = Modifier
                    .width(76.dp)
                    .oceanGlass(14.dp, 0.10f).padding(vertical = 10.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(hour.time, color = mutedWhite, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Image(
                    painter = painterResource(weatherIconRes(hour.code)),
                    contentDescription = weatherDescription(hour.code),
                    modifier = Modifier.size(28.dp)
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "${formatTemp(hour.temperature, useCelsius)}°",
                    color = cream,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(5.dp))
                Text("💧 ${hour.precipitation}%", color = mutedWhite, fontSize = 10.sp)
                Text("Вітер ${hour.wind}", color = mutedWhite, fontSize = 9.sp)
                Text("Хмари ${hour.cloudCover}%", color = mutedWhite, fontSize = 9.sp)
            }
        }
    }
}

@Composable
fun PrecipitationCard(
    hours: List<HourForecast>,
    dailyRainMm: Double,
    dailySnowfallCm: Double,
    modifier: Modifier = Modifier
) {
    val maxProbability = hours.maxByOrNull { it.precipitation } 
    val maxAmount = (hours.maxOfOrNull { it.precipitationMm } ?: 0.0).coerceAtLeast(0.2)
    val rainColor = Color(0xFF8CCBFF)
    val snowColor = Color(0xFFE4F2FF)

    Column(
        modifier = modifier
            .oceanGlass(22.dp).padding(16.dp)
    ) {
        Text("ОПАДИ НА 24 ГОДИНИ", color = mutedWhite, fontSize = 12.sp, letterSpacing = 1.6.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${maxProbability?.precipitation ?: 0}%", color = cream, fontSize = 32.sp, fontWeight = FontWeight.Light)
                Text("Максимальна ймовірність", color = mutedWhite, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(maxProbability?.time ?: "—", color = rainColor, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Text("Час найбільшої ймовірності", color = mutedWhite, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.fillMaxWidth().height(82.dp)) {
            val baseline = size.height - 5.dp.toPx()
            val usableHeight = size.height - 12.dp.toPx()
            val slot = size.width / hours.size.coerceAtLeast(1)
            hours.forEachIndexed { index, hour ->
                val amount = hour.precipitationMm.coerceAtLeast(0.0).toFloat()
                val fraction = (amount / maxAmount.toFloat()).coerceIn(0f, 1f)
                val x = slot * (index + 0.5f)
                val top = baseline - usableHeight * fraction
                drawLine(
                    color = if (hour.snowfallCm > 0.0) snowColor else rainColor,
                    start = Offset(x, baseline),
                    end = Offset(x, top),
                    strokeWidth = (slot * 0.55f).coerceAtLeast(1.dp.toPx()),
                    cap = StrokeCap.Round
                )
            }
            drawLine(Color.White.copy(alpha = 0.22f), Offset(0f, baseline), Offset(size.width, baseline), 1.dp.toPx())
        }
        if (hours.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(hours.first().time, color = mutedWhite, fontSize = 10.sp)
                Text(hours[hours.size / 2].time, color = mutedWhite, fontSize = 10.sp)
                Text(hours.last().time, color = mutedWhite, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = Color.White.copy(alpha = 0.18f))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("🌧 ДОЩ СЬОГОДНІ", color = mutedWhite, fontSize = 10.sp)
                Text("${"%.1f".format(Locale.US, dailyRainMm)} мм", color = rainColor, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            }
            Column(Modifier.weight(1f)) {
                Text("❄ СНІГ СЬОГОДНІ", color = mutedWhite, fontSize = 10.sp)
                Text("${"%.1f".format(Locale.US, dailySnowfallCm)} см", color = snowColor, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            }
        }
        Text("Стовпчики показують прогнозовану кількість опадів за годину", color = mutedWhite, fontSize = 10.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun TemperatureTrendCard(
    hours: List<HourForecast>,
    useCelsius: Boolean,
    modifier: Modifier = Modifier
) {
    val values = hours.map { formatTemp(it.temperature, useCelsius).toFloat() }
    val minTemp = values.minOrNull() ?: return
    val maxTemp = values.maxOrNull() ?: return
    val range = (maxTemp - minTemp).coerceAtLeast(2f)
    val chartMin = minTemp - range * 0.18f
    val chartMax = maxTemp + range * 0.18f
    val lineColor = Color(0xFFFFD18A)

    Column(
        modifier = modifier
            .oceanGlass(22.dp).padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ТЕМПЕРАТУРА НА 24 ГОДИНИ", color = mutedWhite, fontSize = 12.sp, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(4.dp))
                Text("Зміна протягом доби", color = cream, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${maxTemp.toInt()}°", color = cream, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("максимум", color = mutedWhite, fontSize = 10.sp)
            }
        }

        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.fillMaxWidth().height(126.dp)) {
            val left = 8.dp.toPx()
            val right = size.width - 8.dp.toPx()
            val top = 10.dp.toPx()
            val bottom = size.height - 10.dp.toPx()
            val plotWidth = (right - left).coerceAtLeast(1f)
            val plotHeight = (bottom - top).coerceAtLeast(1f)

            for (step in 0..2) {
                val y = top + plotHeight * step / 2f
                drawLine(
                    color = Color.White.copy(alpha = 0.16f),
                    start = Offset(left, y),
                    end = Offset(right, y),
                    strokeWidth = 1.dp.toPx()
                )
            }

            val points = values.mapIndexed { index, value ->
                val xFraction = index.toFloat() / (values.size - 1).coerceAtLeast(1)
                val yFraction = ((chartMax - value) / (chartMax - chartMin)).coerceIn(0f, 1f)
                Offset(left + plotWidth * xFraction, top + plotHeight * yFraction)
            }

            val linePath = Path().apply {
                points.forEachIndexed { index, point ->
                    if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                }
            }
            val areaPath = Path().apply {
                moveTo(points.first().x, bottom)
                points.forEach { lineTo(it.x, it.y) }
                lineTo(points.last().x, bottom)
                close()
            }

            drawPath(
                path = areaPath,
                brush = Brush.verticalGradient(
                    colors = listOf(lineColor.copy(alpha = 0.32f), lineColor.copy(alpha = 0.015f)),
                    startY = top,
                    endY = bottom
                )
            )
            drawPath(path = linePath, color = lineColor, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))

            val currentPoint = points.first()
            drawCircle(Color.White.copy(alpha = 0.35f), radius = 8.dp.toPx(), center = currentPoint)
            drawCircle(Color.White, radius = 4.dp.toPx(), center = currentPoint)
            drawCircle(lineColor, radius = 3.dp.toPx(), center = points.last())
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(hours.first().time, color = mutedWhite, fontSize = 10.sp)
            Text(hours[hours.size / 2].time, color = mutedWhite, fontSize = 10.sp)
            Text(hours.last().time, color = mutedWhite, fontSize = 10.sp)
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = Color.White.copy(alpha = 0.18f))
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("МІНІМУМ", color = mutedWhite, fontSize = 9.sp)
                Text("${minTemp.toInt()}°", color = cream, fontSize = 17.sp)
            }
            Text("● Поточна година", color = mutedWhite, fontSize = 10.sp)
            Column(horizontalAlignment = Alignment.End) {
                Text("МАКСИМУМ", color = mutedWhite, fontSize = 9.sp)
                Text("${maxTemp.toInt()}°", color = cream, fontSize = 17.sp)
            }
        }
    }
}

@Composable
fun VisibilityCard(
    visibilityMeters: Double,
    weatherCode: Int,
    modifier: Modifier = Modifier
) {
    val visibilityKm = (visibilityMeters / 1000.0).coerceAtLeast(0.0)
    val (status, detail) = when {
        visibilityKm < 1.0 -> "Дуже погана" to "Видимість менше 1 км. Будь уважним на дорогах."
        visibilityKm < 4.0 -> "Погана" to "Обмежена видимість. Рухайся обережно."
        visibilityKm < 10.0 -> "Помірна" to "Дальність огляду дещо обмежена."
        else -> "Добра" to "Гарна дальність огляду."
    }
    val fog = weatherCode == 45 || weatherCode == 48
    Column(
        modifier = modifier
            .oceanGlass(22.dp).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("◉", color = cream, fontSize = 22.sp)
            Spacer(Modifier.width(9.dp))
            Text("ВИДИМІСТЬ", color = mutedWhite, fontSize = 12.sp, letterSpacing = 1.4.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                String.format(Locale.US, "%.1f", visibilityKm),
                color = cream,
                fontSize = 34.sp,
                fontWeight = FontWeight.Light
            )
            Spacer(Modifier.width(6.dp))
            Text("км", color = mutedWhite, fontSize = 15.sp, modifier = Modifier.padding(bottom = 5.dp))
            Spacer(Modifier.weight(1f))
            Text(status, color = cream, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(5.dp))
        Text(detail, color = mutedWhite, fontSize = 12.sp)
        if (fog) {
            Spacer(Modifier.height(8.dp))
            Text("Метеоумови відповідають туману або паморозному туману.", color = cream, fontSize = 12.sp)
        }
    }
}

@Composable
fun AtmosphereCard(
    humidity: Int,
    dewPoint: Double,
    cloudCover: Int,
    useCelsius: Boolean,
    modifier: Modifier = Modifier
) {
    val dewPointValue = if (useCelsius) dewPoint else dewPoint * 9.0 / 5.0 + 32.0
    val dewPointLabel = if (useCelsius) "°C" else "°F"
    val moistureStatus = when {
        dewPoint < 10.0 -> "Повітря відносно сухе"
        dewPoint < 16.0 -> "Помірна вологість"
        dewPoint < 20.0 -> "Відчутно волого"
        else -> "Дуже вологе повітря"
    }
    val cloudStatus = when {
        cloudCover < 20 -> "Переважно ясно"
        cloudCover < 50 -> "Мінлива хмарність"
        cloudCover < 85 -> "Хмарно"
        else -> "Переважно похмуро"
    }
    Column(
        modifier = modifier
            .oceanGlass(22.dp).padding(16.dp)
    ) {
        Text("СТАН АТМОСФЕРИ", color = mutedWhite, fontSize = 12.sp, letterSpacing = 1.4.sp)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .oceanGlass(16.dp, 0.10f).padding(12.dp)
            ) {
                Text("ТОЧКА РОСИ", color = mutedWhite, fontSize = 10.sp, letterSpacing = 0.8.sp)
                Spacer(Modifier.height(5.dp))
                Text("${"%.1f".format(Locale.US, dewPointValue)}$dewPointLabel", color = cream, fontSize = 24.sp)
                Text(moistureStatus, color = mutedWhite, fontSize = 11.sp)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .oceanGlass(16.dp, 0.10f).padding(12.dp)
            ) {
                Text("ХМАРНІСТЬ", color = mutedWhite, fontSize = 10.sp, letterSpacing = 0.8.sp)
                Spacer(Modifier.height(5.dp))
                Text("$cloudCover%", color = cream, fontSize = 24.sp)
                Text(cloudStatus, color = mutedWhite, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Відносна вологість: $humidity%", color = mutedWhite, fontSize = 12.sp)
    }
}

@Composable
fun AirQualityCard(air: AirQuality, modifier: Modifier = Modifier) {
    val aqi = air.europeanAqi.coerceAtLeast(0)
    val (label, advice, levelColor) = when {
        aqi <= 20 -> Triple("Добра", "Якість повітря сприятлива", Color(0xFF63C98A))
        aqi <= 40 -> Triple("Задовільна", "Зазвичай без обмежень для прогулянок", Color(0xFFA8CE65))
        aqi <= 60 -> Triple("Помірна", "Чутливим людям варто звернути увагу", Color(0xFFF0C65B))
        aqi <= 80 -> Triple("Погана", "Чутливим людям краще зменшити навантаження надворі", Color(0xFFEE9854))
        aqi <= 100 -> Triple("Дуже погана", "Рекомендується обмежити тривале перебування надворі", Color(0xFFE66D68))
        else -> Triple("Надзвичайно погана", "Варто уникати тривалого перебування надворі", Color(0xFFB66ACB))
    }
    val markerFraction = (aqi.coerceAtMost(120) / 100f).coerceIn(0f, 1f)

    Column(
        modifier = modifier
            .oceanGlass(22.dp).padding(16.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("ЯКІСТЬ ПОВІТРЯ", color = mutedWhite, fontSize = 12.sp, letterSpacing = 1.8.sp)
                Spacer(Modifier.height(5.dp))
                Text(label, color = levelColor, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(advice, color = mutedWhite, fontSize = 12.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(aqi.toString(), color = levelColor, fontSize = 36.sp, fontWeight = FontWeight.Light)
                Text("Європейський AQI", color = mutedWhite, fontSize = 10.sp)
            }
        }

        Spacer(Modifier.height(17.dp))
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(18.dp)) {
            val markerX = (maxWidth - 12.dp) * markerFraction
            Row(
                modifier = Modifier.fillMaxWidth().height(9.dp).align(Alignment.Center)
                    .clip(RoundedCornerShape(50)),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                listOf(
                    Color(0xFF63C98A), Color(0xFFA8CE65), Color(0xFFF0C65B),
                    Color(0xFFEE9854), Color(0xFFE66D68)
                ).forEach { segmentColor ->
                    Box(Modifier.weight(1f).fillMaxHeight().background(segmentColor))
                }
            }
            Box(
                modifier = Modifier.offset(x = markerX).size(width = 12.dp, height = 18.dp)
                    .clip(RoundedCornerShape(50)).background(Color.White)
                    .align(Alignment.CenterStart)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0", "20", "40", "60", "80", "100+").forEach {
                Text(it, color = mutedWhite, fontSize = 9.sp)
            }
        }

        Spacer(Modifier.height(13.dp))
        HorizontalDivider(color = Color.White.copy(alpha = 0.18f))
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("PM2.5", color = mutedWhite, fontSize = 11.sp)
                Text("${"%.1f".format(Locale.US, air.pm25)} µg/m³", color = cream, fontSize = 16.sp)
            }
            Column(Modifier.weight(1f)) {
                Text("PM10", color = mutedWhite, fontSize = 11.sp)
                Text("${"%.1f".format(Locale.US, air.pm10)} µg/m³", color = cream, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Дані на ${air.updatedAt}", color = mutedWhite, fontSize = 10.sp)
    }
}

@Composable
fun WeatherMetric(title: String, value: String, iconRes: Int, modifier: Modifier = Modifier) {
    Column(
        modifier.oceanGlass(24.dp, 0.20f).padding(horizontal = 18.dp, vertical = 17.dp)
    ) {
        Image(painter = painterResource(iconRes), contentDescription = title, modifier = Modifier.size(31.dp))
        Spacer(Modifier.height(10.dp))
        Text(value, color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(title, color = Color.White.copy(alpha = 0.86f), fontSize = 13.sp)
    }
}


@Composable
private fun PressureCard(pressureHpa: Double, modifier: Modifier = Modifier) {
    val pressureMmhg = pressureHpa * 0.750061683
    val label = when {
        pressureHpa < 1000.0 -> "Знижений"
        pressureHpa > 1020.0 -> "Підвищений"
        else -> "Нормальний"
    }
    Column(
        modifier = modifier.oceanGlass(24.dp, 0.20f).height(148.dp).padding(17.dp)
    ) {
        Text("АТМОСФЕРНИЙ ТИСК", color = mutedWhite, fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(8.dp))
        Text("${pressureMmhg.toInt()} мм", color = cream, fontSize = 23.sp, fontWeight = FontWeight.Medium)
        Text(label, color = mutedWhite, fontSize = 12.sp)
    }
}

@Composable
private fun WindDirectionCard(direction: Int, modifier: Modifier = Modifier) {
    val directions = listOf("Пн", "Пн-Сх", "Сх", "Пд-Сх", "Пд", "Пд-Зх", "Зх", "Пн-Зх")
    val normalized = ((direction % 360) + 360) % 360
    val compass = directions[((normalized + 22) / 45) % 8]
    Column(
        modifier = modifier.oceanGlass(24.dp, 0.20f).height(148.dp).padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("НАПРЯМОК ВІТРУ", color = mutedWhite, fontSize = 10.sp, letterSpacing = 1.sp)
        Spacer(Modifier.height(3.dp))
        Canvas(Modifier.size(38.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = size.minDimension * 0.40f
            drawCircle(color = Color.White.copy(alpha = 0.28f), radius = radius, center = center, style = Stroke(width = 2.dp.toPx()))
            val radians = Math.toRadians(direction.toDouble())
            val dx = kotlin.math.sin(radians).toFloat()
            val dy = -kotlin.math.cos(radians).toFloat()
            val tip = Offset(center.x + dx * radius * 0.82f, center.y + dy * radius * 0.82f)
            drawLine(color = Color(0xFFFFC978), start = center, end = tip, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(color = cream, radius = 3.dp.toPx(), center = center)
        }
        Text(compass, color = cream, fontSize = 18.sp, fontWeight = FontWeight.Medium)
        Text("$normalized°", color = mutedWhite, fontSize = 11.sp)
    }
}

@Composable
fun SettingsScreen(useCelsius: Boolean, onCelsiusChange: (Boolean) -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF063B46),
                        Color(0xFF087E83),
                        Color(0xFF123D50)
                    )
                )
            )
            .padding(24.dp)
    ) {
        TextButton(onClick = onBack) { Text("← Назад", color = cream) }
        Spacer(Modifier.height(24.dp))
        Text("Налаштування", color = cream, fontSize = 30.sp, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(30.dp))
        Text("Одиниці вимірювання", color = mutedWhite, fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Цельсій (°C)", color = cream, modifier = Modifier.weight(1f))
            Switch(checked = useCelsius, onCheckedChange = onCelsiusChange)
        }
        Spacer(Modifier.height(20.dp))
        Text("Версія 1.12", color = mutedWhite)
        Text("Погодні дані надає Open-Meteo", color = mutedWhite, fontSize = 12.sp)
    }
}

private fun getLastKnownLocation(context: Context): Location? {
    if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
    return try {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val gps = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        val network = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        listOfNotNull(gps, network).maxByOrNull { it.time }
    } catch (_: Exception) { null }
}

private fun getFreshLocation(
    context: Context,
    onResult: (Location?) -> Unit
) {
    val fineGranted =
        context.checkSelfPermission(
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    val coarseGranted =
        context.checkSelfPermission(
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    if (!fineGranted && !coarseGranted) {
        onResult(null)
        return
    }

    try {
        val fusedClient =
            LocationServices.getFusedLocationProviderClient(context)

        val cancellationToken = CancellationTokenSource()

        fusedClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            cancellationToken.token
        )
            .addOnSuccessListener { location ->
                onResult(location)
            }
            .addOnFailureListener {
                onResult(null)
            }

    } catch (_: SecurityException) {
        onResult(null)
    } catch (_: Exception) {
        onResult(null)
    }
}
@Composable
fun SolarUvCard(solar: SolarUv, modifier: Modifier = Modifier) {
    val (uvLabel, uvAdvice, uvColor) = when {
        solar.maxUv < 3 -> Triple("Низький", "Зазвичай достатньо звичайних заходів захисту", Color(0xFF63C98A))
        solar.maxUv < 6 -> Triple("Помірний", "За тривалого перебування надворі потрібен захист", Color(0xFFF0C65B))
        solar.maxUv < 8 -> Triple("Високий", "Шукай тінь, одягай окуляри та використовуй SPF 30+", Color(0xFFEE9854))
        solar.maxUv < 11 -> Triple("Дуже високий", "Обмеж перебування на сонці опівдні, використовуй SPF 30+", Color(0xFFE66D68))
        else -> Triple("Екстремальний", "Уникай прямого сонця, шукай тінь та використовуй SPF 50+", Color(0xFFB66ACB))
    }
    Column(
        modifier = modifier.oceanGlass(22.dp).padding(16.dp)
    ) {
        Text("СОНЦЕ ТА УФ-ІНДЕКС", color = mutedWhite, fontSize = 12.sp, letterSpacing = 1.8.sp)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Схід сонця", color = mutedWhite, fontSize = 11.sp)
                Text("☀  ${solar.sunrise}", color = cream, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            }
            Column(Modifier.weight(1f)) {
                Text("Захід сонця", color = mutedWhite, fontSize = 11.sp)
                Text("🌇  ${solar.sunset}", color = cream, fontSize = 20.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Світловий день: ${solar.daylight}", color = mutedWhite, fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = Color.White.copy(alpha = 0.18f))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(uvLabel, color = uvColor, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Максимум за день: ${"%.1f".format(Locale.US, solar.maxUv)}", color = mutedWhite, fontSize = 11.sp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${"%.1f".format(Locale.US, solar.currentUv)}", color = uvColor, fontSize = 38.sp, fontWeight = FontWeight.Light)
                Text("УФ зараз", color = mutedWhite, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(uvAdvice, color = cream, fontSize = 12.sp)
    }
}

private fun fetchWeather(lat: Double, lon: Double, city: String): WeatherBundle {
    val endpoint = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_direction_10m,surface_pressure,uv_index,visibility,dew_point_2m,cloud_cover&hourly=temperature_2m,precipitation_probability,precipitation,rain,snowfall,weather_code,wind_speed_10m,cloud_cover,uv_index&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max,rain_sum,snowfall_sum,precipitation_sum,precipitation_probability_max,wind_speed_10m_max&timezone=auto&forecast_days=7"
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"; connectTimeout = 12000; readTimeout = 12000
    }
    try {
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val root = JSONObject(body)
        val current = root.getJSONObject("current")
        val daily = root.getJSONObject("daily")
        val hourly = root.getJSONObject("hourly")
        val hourlyTimes = hourly.getJSONArray("time")
        val hourlyTemps = hourly.getJSONArray("temperature_2m")
        val hourlyRain = hourly.getJSONArray("precipitation_probability")
        val hourlyPrecipitation = hourly.getJSONArray("precipitation")
        val hourlyRainMm = hourly.getJSONArray("rain")
        val hourlySnowfallCm = hourly.getJSONArray("snowfall")
        val hourlyCodes = hourly.getJSONArray("weather_code")
        val hourlyWind = hourly.getJSONArray("wind_speed_10m")
        val hourlyCloudCover = hourly.optJSONArray("cloud_cover")
        val hourlyUvIndex = hourly.optJSONArray("uv_index")
        val nowTime = root.getJSONObject("current").getString("time").take(13)
        val startHour = (0 until hourlyTimes.length()).firstOrNull { hourlyTimes.getString(it).startsWith(nowTime) } ?: 0
        fun hourForecastAt(i: Int): HourForecast {
            val timeLabel = hourlyTimes.getString(i).substringAfter("T")
            return HourForecast(
                timeLabel, hourlyTemps.getDouble(i).toInt(), hourlyCodes.getInt(i),
                hourlyRain.getInt(i), hourlyWind.getDouble(i).toInt(),
                hourlyPrecipitation.optDouble(i, 0.0), hourlyRainMm.optDouble(i, 0.0),
                hourlySnowfallCm.optDouble(i, 0.0),
                hourlyCloudCover?.optInt(i, 0) ?: 0,
                hourlyUvIndex?.optDouble(i, 0.0) ?: 0.0
            )
        }
        val hours = (startHour until minOf(startHour + 24, hourlyTimes.length()))
            .map { i -> hourForecastAt(i) }
        val hours48 = (startHour until minOf(startHour + 48, hourlyTimes.length()))
            .map { i -> hourForecastAt(i) }
        val times = daily.getJSONArray("time")
        val codes = daily.getJSONArray("weather_code")
        val highs = daily.getJSONArray("temperature_2m_max")
        val lows = daily.getJSONArray("temperature_2m_min")
        val days = (0 until times.length()).map { i ->
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(times.getString(i))
            val label = if (i == 0) "Сьогодні" else SimpleDateFormat("EEEE", Locale("uk", "UA")).format(date ?: Date()).replaceFirstChar { it.uppercase() }
            DayForecast(label, codes.getInt(i), highs.getDouble(i).toInt(), lows.getDouble(i).toInt())
        }
        val airQuality = try {
            fetchAirQuality(lat, lon)
        } catch (_: Exception) {
            null
        }
        val sunrise = daily.getJSONArray("sunrise").getString(0).substringAfter("T").take(5)
        val sunset = daily.getJSONArray("sunset").getString(0).substringAfter("T").take(5)
        val sunriseParts = sunrise.split(":").map { it.toInt() }
        val sunsetParts = sunset.split(":").map { it.toInt() }
        val daylightMinutes = (sunsetParts[0] * 60 + sunsetParts[1]) - (sunriseParts[0] * 60 + sunriseParts[1])
        val solarUv = SolarUv(
            sunrise = sunrise,
            sunset = sunset,
            daylight = "${daylightMinutes / 60} год ${daylightMinutes % 60} хв",
            currentUv = current.optDouble("uv_index", 0.0),
            maxUv = daily.getJSONArray("uv_index_max").optDouble(0, 0.0)
        )
        val rainSums = daily.optJSONArray("rain_sum")
        val snowSums = daily.optJSONArray("snowfall_sum")
        val precipitationMax = daily.optJSONArray("precipitation_probability_max")
        val windMax = daily.optJSONArray("wind_speed_10m_max")
        fun dailyOutlook(index: Int): DailyOutlook = DailyOutlook(
            high = highs.optDouble(index, 0.0).toInt(),
            low = lows.optDouble(index, 0.0).toInt(),
            weatherCode = codes.optInt(index, 0),
            rainMm = rainSums?.optDouble(index, 0.0) ?: 0.0,
            snowCm = snowSums?.optDouble(index, 0.0) ?: 0.0,
            precipitationProbability = precipitationMax?.optInt(index, 0) ?: 0,
            maxWind = windMax?.optDouble(index, 0.0)?.toInt() ?: 0
        )

        return WeatherBundle(
            WeatherNow(current.getDouble("temperature_2m").toInt(), current.getDouble("apparent_temperature").toInt(),
                current.getInt("relative_humidity_2m"), current.getDouble("wind_speed_10m").toInt(),
                current.getInt("weather_code"), highs.getDouble(0).toInt(), lows.getDouble(0).toInt(),
                current.optDouble("surface_pressure", 1013.25), current.optInt("wind_direction_10m", 0), city,
                current.optDouble("visibility", 10000.0),
                current.optDouble("dew_point_2m", 0.0),
                current.optInt("cloud_cover", 0)),
            days,
            hours,
            hours48,
            airQuality,
            solarUv,
            todayOutlook = dailyOutlook(0),
            tomorrowOutlook = dailyOutlook(1),
            dailyRainMm = rainSums?.optDouble(0, 0.0) ?: 0.0,
            dailySnowfallCm = snowSums?.optDouble(0, 0.0) ?: 0.0
        )
    } finally { connection.disconnect() }
}

private fun fetchAirQuality(lat: Double, lon: Double): AirQuality? {
    val endpoint =
        "https://air-quality-api.open-meteo.com/v1/air-quality" +
        "?latitude=$lat&longitude=$lon" +
        "&current=european_aqi,pm2_5,pm10" +
        "&timezone=auto"

    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 8000
        readTimeout = 8000
    }

    try {
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val current = JSONObject(body).getJSONObject("current")
        val observationTime = current.optString("time", "")
        val displayTime = observationTime.substringAfter("T", observationTime).take(5)
        return AirQuality(
            europeanAqi = current.getDouble("european_aqi").toInt(),
            pm25 = current.getDouble("pm2_5"),
            pm10 = current.getDouble("pm10"),
            updatedAt = displayTime.ifBlank { "час невідомий" }
        )
    } finally {
        connection.disconnect()
    }
}

private fun formatTemp(celsius: Int, useCelsius: Boolean): Int = if (useCelsius) celsius else (celsius * 9.0 / 5.0 + 32).toInt()

private fun weatherIconRes(code: Int): Int = when (code) {
    0 -> R.drawable.weather_sunny
    1, 2 -> R.drawable.weather_partly
    3 -> R.drawable.weather_overcast
    45, 48 -> R.drawable.weather_fog
    51, 53, 55, 56, 57 -> R.drawable.weather_drizzle
    61, 63, 65, 66, 67, 80, 81, 82 -> R.drawable.weather_rain
    71, 73, 75, 77, 85, 86 -> R.drawable.weather_snow
    95, 96, 99 -> R.drawable.weather_storm
    else -> R.drawable.weather_partly
}

private fun weatherDescription(code: Int): String = when (code) {
    0 -> "Ясно"; 1 -> "Переважно ясно"; 2 -> "Мінлива хмарність"; 3 -> "Хмарно"
    45, 48 -> "Туман"; 51, 53, 55 -> "Мряка"; 61, 63, 65 -> "Дощ"
    71, 73, 75 -> "Сніг"; 80, 81, 82 -> "Зливи"; 95, 96, 99 -> "Гроза"
    else -> "Змінна погода"
}
private fun getCityName(
    context: Context,
    latitude: Double,
    longitude: Double
): String {

    // Координати центру Десни
    val desnaLatitude = 50.9311535
    val desnaLongitude = 30.75695

    // Перевірка відстані до Десни
    val results = FloatArray(1)

    Location.distanceBetween(
        latitude,
        longitude,
        desnaLatitude,
        desnaLongitude,
        results
    )

    // Якщо відстань не більше 10 км — показуємо Десна
    if (results[0] <= 10000f) {
        return "Десна"
    }

    // В інших місцях визначаємо назву автоматично
    return try {
        val geocoder = Geocoder(context, Locale("uk", "UA"))

        @Suppress("DEPRECATION")
        val address = geocoder.getFromLocation(
            latitude,
            longitude,
            5
        )?.firstOrNull()

        address?.locality
            ?: address?.subAdminArea
            ?: address?.adminArea
            ?: "Моє місцезнаходження"

    } catch (e: Exception) {
        "Моє місцезнаходження"
    }
}
