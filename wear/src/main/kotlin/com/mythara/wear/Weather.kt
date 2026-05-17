package com.mythara.wear

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/** Current-conditions readout for the PTT screen. Temperature is in
 *  degrees Fahrenheit (converted at source from Open-Meteo's Celsius
 *  payload) so every display site can just slap "°F" on it. */
data class WeatherInfo(val tempF: Int, val label: String)

sealed interface WeatherResult {
    data class Ok(val info: WeatherInfo) : WeatherResult
    data object NoPermission : WeatherResult
    data object NoLocation : WeatherResult
    data class Error(val reason: String) : WeatherResult
}

private const val TAG = "Mythara/Weather"

/**
 * Resolve the watch's coarse location and fetch current conditions from
 * Open-Meteo — free, no API key, just lat/lon. All network + JSON work
 * runs on [Dispatchers.IO]; the caller drives it from a coroutine.
 */
suspend fun fetchWeather(context: Context): WeatherResult = withContext(Dispatchers.IO) {
    val fine = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) return@withContext WeatherResult.NoPermission

    val loc = runCatching { resolveLocation(context) }.getOrNull()
        ?: return@withContext WeatherResult.NoLocation

    runCatching { fetchOpenMeteo(loc.latitude, loc.longitude) }
        .fold(
            onSuccess = { WeatherResult.Ok(it) },
            onFailure = {
                Log.w(TAG, "open-meteo fetch failed: ${it.message}")
                WeatherResult.Error(it.message ?: "fetch failed")
            },
        )
}

/**
 * Best-effort location: the freshest last-known fix across every
 * provider (instant if cached), falling back to a single bounded
 * current-location request. Permission is checked by the caller.
 */
@SuppressLint("MissingPermission")
private suspend fun resolveLocation(context: Context): Location? {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

    val lastKnown = lm.allProviders
        .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
        .maxByOrNull { it.time }
    if (lastKnown != null) return lastKnown

    val provider = when {
        runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false) ->
            LocationManager.GPS_PROVIDER
        runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false) ->
            LocationManager.NETWORK_PROVIDER
        else -> return null
    }
    return withTimeoutOrNull(15_000L) {
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            lm.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                if (cont.isActive) cont.resume(location)
            }
        }
    }
}

private fun fetchOpenMeteo(lat: Double, lon: Double): WeatherInfo {
    val url = URL(
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,weather_code",
    )
    val conn = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 8_000
        requestMethod = "GET"
    }
    try {
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val current = JSONObject(body).getJSONObject("current")
        val tempC = current.getDouble("temperature_2m")
        val tempF = tempC * 9.0 / 5.0 + 32.0
        val code = current.getInt("weather_code")
        return WeatherInfo(tempF = Math.round(tempF).toInt(), label = wmoLabel(code))
    } finally {
        conn.disconnect()
    }
}

/**
 * WMO weather-code → short label. Open-Meteo reports the WMO 4677 code
 * table; we collapse it to the bands that matter on a wrist readout.
 */
private fun wmoLabel(code: Int): String = when (code) {
    0 -> "Clear"
    1, 2 -> "Partly cloudy"
    3 -> "Overcast"
    45, 48 -> "Fog"
    in 51..57 -> "Drizzle"
    in 61..67 -> "Rain"
    in 71..77 -> "Snow"
    in 80..82 -> "Showers"
    85, 86 -> "Snow showers"
    in 95..99 -> "Storm"
    else -> "—"
}
