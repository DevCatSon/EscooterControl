package com.devcat.escootercontrol.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.devcat.escootercontrol.ui.components.*
import com.devcat.escootercontrol.ble.ScooterTelemetry
import com.devcat.escootercontrol.data.ScooterStore
import com.devcat.escootercontrol.ui.theme.AppThemeMode


/** General scooter and app preferences. Ride mode now lives only on the dashboard. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScooterSettingsScreen(
    telemetry: ScooterTelemetry,
    onBack: () -> Unit,
    onSetSpeedUnit: (Boolean) -> Unit,
    onQueryVersions: () -> Unit,
    scooterAddress: String?,
    advertisedName: String?,
    nickname: String?,
    onRename: (String) -> Unit,
    onForget: () -> Unit,
    onOpenSensors: () -> Unit,
    onOpenTuning: () -> Unit,
    themeMode: AppThemeMode,
    onThemeModeChanged: (AppThemeMode) -> Unit,
    onRunSetupAgain: () -> Unit,
    isDemoMode: Boolean
) {
    var versionAsked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        onQueryVersions()
        versionAsked = true
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    PremiumGlassIconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SettingsCard(
                title = "Appearance",
                subtitle = "Choose the look you want. System follows Android and uses dynamic color when supported."
            ) {
                ThemePicker(
                    selected = themeMode,
                    onSelected = onThemeModeChanged
                )
                if (themeMode == AppThemeMode.MIDNIGHT) {
                    HintText("Midnight uses a deep blue-black palette for a calmer low-light interface.")
                }
            }

            SettingsCard(
                title = "App setup",
                subtitle = if (isDemoMode) {
                    "You're currently exploring a simulated scooter session."
                } else {
                    "Run the welcome, pairing, naming and appearance setup again."
                }
            ) {
                PremiumGlassOutlinedButton(onClick = onRunSetupAgain, modifier = Modifier.fillMaxWidth()) {
                    Text("Run setup again", maxLines = 1)
                }
                if (isDemoMode) {
                    HintText("Restarting setup exits Demo Mode first, so you can pair a real scooter or start a fresh demo.")
                }
            }

            SettingsCard(
                title = "Speed unit",
                subtitle = "Stored on the scooter. Speed, trip and distance in this app follow it."
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = telemetry.speedUnitMiles == false,
                        onClick = { onSetSpeedUnit(false) },
                        label = { Text("km/h") }
                    )
                    FilterChip(
                        selected = telemetry.speedUnitMiles == true,
                        onClick = { onSetSpeedUnit(true) },
                        label = { Text("mph") }
                    )
                }
                if (telemetry.speedUnitMiles == null) HintText("Waiting for scooter status…")
            }

            SettingsCard(
                title = "Speed & performance",
                subtitle = "Manufacturer-supported speed and ride tuning reported by the connected scooter."
            ) {
                PremiumGlassOutlinedButton(onClick = onOpenTuning, modifier = Modifier.fillMaxWidth()) {
                    Text("Open speed & performance")
                }
            }

            SettingsCard(
                title = "Scooter name",
                subtitle = "A nickname shown in this app only. Bluetooth can't rename the scooter itself."
            ) {
                var text by remember(scooterAddress, nickname) { mutableStateOf(nickname ?: "") }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(ScooterStore.MAX_NICKNAME) },
                    singleLine = true,
                    label = { Text(advertisedName ?: "My Scooter") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PremiumGlassButton(
                        onClick = { scooterAddress?.let { onRename(text) } },
                        enabled = scooterAddress != null && text.trim() != (nickname ?: "")
                    ) { Text("Save") }
                    PremiumGlassOutlinedButton(
                        onClick = onForget,
                        tint = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.16f),
                        contentColor = MaterialTheme.colorScheme.error
                    ) {
                        Text("Forget this scooter", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                HintText("Forget disconnects this scooter and disables auto-reconnect until the next successful connection.")
            }

            SettingsCard(
                title = "Version",
                subtitle = "Read from the scooter. This section never changes firmware or configuration."
            ) {
                VersionRow("Dashboard hardware", telemetry.instrumentHardwareVersion)
                VersionRow("Dashboard software", telemetry.instrumentSoftwareVersion)
                VersionRow("Controller hardware", telemetry.controllerHardwareVersion)
                VersionRow("Controller software", telemetry.controllerSoftwareVersion)
                if (versionAsked && telemetry.instrumentHardwareVersion == null && telemetry.controllerHardwareVersion == null) {
                    HintText("Nothing received yet. Tap Query again to ask the dashboard directly.")
                }
                PremiumGlassOutlinedButton(onClick = onQueryVersions) { Text("Query again") }
            }

            PremiumGlassOutlinedButton(onClick = onOpenSensors, modifier = Modifier.fillMaxWidth()) {
                Text("Live sensors & battery")
            }


            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun ThemePicker(
    selected: AppThemeMode,
    onSelected: (AppThemeMode) -> Unit
) {
    val options = listOf(
        AppThemeMode.SYSTEM to "System",
        AppThemeMode.LIGHT to "Light",
        AppThemeMode.DARK to "Dark",
        AppThemeMode.MIDNIGHT to "Midnight"
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { (mode, label) ->
                    val isSelected = selected == mode
                    PremiumGlassSurface(
                        onClick = { onSelected(mode) },
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        selectedTint = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent,
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.70f)
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.36f)
                        )
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                label,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    PremiumGlassCard(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
        }
    }
}

@Composable
private fun VersionRow(label: String, value: Int?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(
            value?.toString() ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun HintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
