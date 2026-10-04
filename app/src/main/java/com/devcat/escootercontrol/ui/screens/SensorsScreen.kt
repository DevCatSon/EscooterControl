package com.devcat.escootercontrol.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.devcat.escootercontrol.ui.components.*
import com.devcat.escootercontrol.ble.BmsInfo
import com.devcat.escootercontrol.ble.ScooterTelemetry

/**
 * Live throttle / brake inputs (CODE 19) and battery-pack data (CODE 31, if the scooter sends it).
 * Handy for hardware testing: squeeze a brake or twist the throttle and watch which value moves.
 * "Peak" is the highest value seen since this page opened, so quick presses aren't missed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorsScreen(telemetry: ScooterTelemetry, onBack: () -> Unit) {
    var peaks by remember { mutableStateOf(mapOf<String, Int>()) }

    val readings = listOf(
        "Throttle" to telemetry.throttle,
        "Throttle (raw sensor)" to telemetry.throttleRaw,
        "Brake 1" to telemetry.brake1,
        "Brake 1 (raw sensor)" to telemetry.brake1Raw,
        "Brake 2" to telemetry.brake2,
        "Brake 2 (raw sensor)" to telemetry.brake2Raw
    )
    LaunchedEffect(telemetry.throttle, telemetry.throttleRaw, telemetry.brake1, telemetry.brake1Raw, telemetry.brake2, telemetry.brake2Raw) {
        val updated = peaks.toMutableMap()
        for ((label, value) in readings) {
            if (value != null && value > (updated[label] ?: Int.MIN_VALUE)) updated[label] = value
        }
        peaks = updated
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text("Live sensors") },
                navigationIcon = {
                    PremiumGlassIconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            PremiumGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Throttle and brakes", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Straight from the scooter, updated about once a second. Units and scale aren't documented, " +
                            "so use the peak to see how far each input travels.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    for ((label, value) in readings) {
                        SensorRow(label, value, peaks[label])
                    }
                    TextButton(onClick = { peaks = emptyMap() }) { Text("Reset peaks") }
                }
            }

            PremiumGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Battery pack", style = MaterialTheme.typography.titleMedium)
                    val bms = telemetry.bms
                    if (bms == null) {
                        Text(
                            "This scooter doesn't send battery-pack data. Voltage and charge level are on the main screen.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        BmsRows(bms)
                    }
                }
            }
        }
    }
}

@Composable
private fun SensorRow(label: String, value: Int?, peak: Int?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                "${value ?: "\u2014"}   (peak ${peak ?: "\u2014"})",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        val progress = if (value != null && peak != null && peak > 0) (value.toFloat() / peak).coerceIn(0f, 1f) else 0f
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun BmsRows(b: BmsInfo) {
    val lines = buildList {
        add("Voltage" to "%.2f V".format(b.voltage))
        add("Current" to "%.2f A".format(b.current))
        b.remainingCapacity?.let { add("Remaining capacity" to "$it") }
        b.ratedCapacity?.let { add("Rated capacity" to "$it") }
        b.cycles?.let { add("Charge cycles" to "$it") }
        b.temperature?.let { add("Temperature" to "$it") }
        add("Charging" to if (b.charging) "yes" else "no")
        add("Discharging" to if (b.discharging) "yes" else "no")
        if (b.lowVoltageProtection) add("Protection" to "low voltage")
        if (b.overVoltageProtection) add("Protection" to "over voltage")
        if (b.anomaly) add("Battery" to "reports an anomaly")
    }
    for ((label, value) in lines) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Text(
        "Layout taken from the stock app; capacity and temperature units are unconfirmed.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
