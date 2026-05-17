package com.mythara.ui.dashboard.tiles

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mythara.agent.tools.ReadSensorsTool
import com.mythara.ui.dashboard.DashboardTileFrame
import com.mythara.ui.theme.Glyph
import com.mythara.ui.theme.MytharaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * Live sensor mini-gauges. Refreshes on init + every 30s — same
 * throttle pattern as NotificationImageIngestor so the tile doesn't
 * become a battery hog by polling sensors on every recompose.
 */
@HiltViewModel
class SensorsTileViewModel @Inject constructor(
    private val sensorsTool: ReadSensorsTool,
) : ViewModel() {

    data class Summary(
        val batteryPct: Int?,
        val batteryTempC: Double?,
        val lightLux: Double?,
        val connectivity: String?,
        val storagePct: Int?,
    )

    private val _summary = MutableStateFlow(Summary(null, null, null, null, null))
    val summary: StateFlow<Summary> = _summary.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                runCatching { refresh() }
                delay(REFRESH_MS)
            }
        }
    }

    private suspend fun refresh() {
        val snap: JsonObject = runCatching { sensorsTool.collectSnapshot() }.getOrNull() ?: return
        val battery = (snap["battery"] as? JsonObject)
        val env = (snap["environment"] as? JsonObject)
        val conn = (snap["connectivity"] as? JsonObject)
        val storage = (snap["storage"] as? JsonObject)
        val lux = (env?.get("light_lux") as? JsonObject)?.get("values")
            ?.let { runCatching { it.jsonPrimitive.content.toDoubleOrNull() }.getOrNull() }
        _summary.value = Summary(
            batteryPct = battery?.get("percent")?.jsonPrimitive?.content?.toIntOrNull(),
            batteryTempC = battery?.get("temperature_c")?.jsonPrimitive?.content?.toDoubleOrNull(),
            lightLux = lux,
            connectivity = conn?.get("type")?.jsonPrimitive?.content,
            storagePct = storage?.get("free_pct")?.jsonPrimitive?.content?.toIntOrNull(),
        )
    }

    companion object {
        private const val REFRESH_MS = 30_000L
    }
}

@Composable
fun SensorsTile(onExpand: () -> Unit) {
    val vm: SensorsTileViewModel = hiltViewModel()
    val s by vm.summary.collectAsState()
    DashboardTileFrame(
        title = "${Glyph.DiamondFilled} sensors",
        accent = MytharaColors.Citron,
        badge = "live",
        onTap = onExpand,
    ) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            metric("battery", s.batteryPct?.let { "$it%" } ?: "—")
            metric("temp", s.batteryTempC?.let { "%.1f°F".format(it * 9.0 / 5.0 + 32.0) } ?: "—")
            metric("light", s.lightLux?.let { "${it.toInt()} lux" } ?: "—")
            metric("net", s.connectivity ?: "—")
            metric("free", s.storagePct?.let { "$it%" } ?: "—")
        }
    }
}

@Composable
private fun metric(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MytharaColors.FgDim, style = MaterialTheme.typography.labelSmall)
        Text(
            text = value,
            color = MytharaColors.Fg,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
        )
    }
}
