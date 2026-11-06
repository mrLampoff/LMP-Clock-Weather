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
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
        window.statusBarColor = android.graphics.Color.rgb(57, 45, 53)
        window.navigationBarColor = android.graphics.Color.rgb(33, 31, 42)
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
    val city: String = "Київ"
)

data class DayForecast(val day: String, val code: Int, val high: Int, val low: Int)

data class WeatherBundle(val now: WeatherNow, val days: List<DayForecast>)

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

@Composable
fun MainWeatherScreen(
    weather: WeatherBundle?, loading: Boolean, error: String, locationName: String,
    useCelsius: Boolean, onRefresh: () -> Unit, onSettings: () -> Unit, onLocation: () -> Unit
) {
    var clockNow by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { clockNow = Date(); delay(1000) } }
    val background = Brush.verticalGradient(
        listOf(Color(0xFF51404A), Color(0xFF9B665B), Color(0xFFE4A36E), Color(0xFF514B55), Color(0xFF252A35))
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(background).navigationBarsPadding(),
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Оновити", tint = cream) }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Налаштування", tint = cream) }
            }
            Spacer(Modifier.height(16.dp))
            Text(SimpleDateFormat("HH:mm", Locale("uk", "UA")).format(clockNow), color = cream, fontSize = 86.sp, fontWeight = FontWeight.Light, letterSpacing = (-4).sp, textAlign = TextAlign.Center, lineHeight = 94.sp)
            Text(SimpleDateFormat("EEEE, d MMMM", Locale("uk", "UA")).format(clockNow).replaceFirstChar { it.uppercase() }, color = mutedWhite, fontSize = 20.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.LocationOn, null, tint = cream, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(4.dp))
                Text(locationName, color = cream, fontSize = 18.sp)
            }
            Spacer(Modifier.height(28.dp))
            if (weather != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Image(painter = painterResource(weatherIconRes(weather.now.code)), contentDescription = weatherDescription(weather.now.code), modifier = Modifier.size(76.dp))
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text("${formatTemp(weather.now.temperature, useCelsius)}°", color = cream, fontSize = 76.sp, fontWeight = FontWeight.Light, lineHeight = 80.sp)
                        Text(weatherDescription(weather.now.code), color = mutedWhite, fontSize = 17.sp)
                    }
                }
                Text("Відчувається як ${formatTemp(weather.now.feelsLike, useCelsius)}°", color = mutedWhite, fontSize = 16.sp)
                Spacer(Modifier.height(10.dp))
                Text("Макс. ${formatTemp(weather.now.high, useCelsius)}°   ·   Мін. ${formatTemp(weather.now.low, useCelsius)}°", color = cream, fontSize = 18.sp)
                Spacer(Modifier.height(28.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WeatherMetric("Вологість", "${weather.now.humidity}%", R.drawable.weather_humidity, Modifier.weight(1f))
                    WeatherMetric("Вітер", "${weather.now.wind} км/год", R.drawable.weather_wind, Modifier.weight(1f))
                }
                Spacer(Modifier.height(24.dp))
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

@Composable
fun WeatherMetric(title: String, value: String, iconRes: Int, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = .13f)).padding(15.dp)) {
        Image(painter = painterResource(iconRes), contentDescription = title, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(7.dp))
        Text(value, color = cream, fontSize = 21.sp, fontWeight = FontWeight.Medium)
        Text(title, color = mutedWhite, fontSize = 12.sp)
    }
}

@Composable
fun SettingsScreen(useCelsius: Boolean, onCelsiusChange: (Boolean) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF51404A), Color(0xFF252A35)))).padding(24.dp)) {
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
        Text("Версія 1.0", color = mutedWhite)
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
private fun fetchWeather(lat: Double, lon: Double, city: String): WeatherBundle {
    val endpoint = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m&daily=weather_code,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=7"
    val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"; connectTimeout = 12000; readTimeout = 12000
    }
    try {
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        val root = JSONObject(body)
        val current = root.getJSONObject("current")
        val daily = root.getJSONObject("daily")
        val times = daily.getJSONArray("time")
        val codes = daily.getJSONArray("weather_code")
        val highs = daily.getJSONArray("temperature_2m_max")
        val lows = daily.getJSONArray("temperature_2m_min")
        val days = (0 until times.length()).map { i ->
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(times.getString(i))
            val label = if (i == 0) "Сьогодні" else SimpleDateFormat("EEEE", Locale("uk", "UA")).format(date ?: Date()).replaceFirstChar { it.uppercase() }
            DayForecast(label, codes.getInt(i), highs.getDouble(i).toInt(), lows.getDouble(i).toInt())
        }
        return WeatherBundle(
            WeatherNow(current.getDouble("temperature_2m").toInt(), current.getDouble("apparent_temperature").toInt(),
                current.getInt("relative_humidity_2m"), current.getDouble("wind_speed_10m").toInt(),
                current.getInt("weather_code"), highs.getDouble(0).toInt(), lows.getDouble(0).toInt(), city), days
        )
    } finally { connection.disconnect() }
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

    // Якщо відстань не більше 2 км — показуємо Десна
    if (results[0] <= 2000f) {
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
