package com.devcat.escootercontrol.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devcat.escootercontrol.ui.components.*
import com.devcat.escootercontrol.ble.ProtocolCodec
import com.devcat.escootercontrol.ble.DashboardTelemetry
import com.devcat.escootercontrol.ble.Units
import com.devcat.escootercontrol.data.FeatureFlags
import kotlin.math.roundToInt

private val ActiveAmber = Color(0xFF8A5B00)
private val ActiveBlue = Color(0xFF285FA8)
private val ActiveGreen = Color(0xFF26724B)

private data class RideModeVisual(
    val label: String,
    val accent: Color
)

private val rideModes = mapOf(
    1 to RideModeVisual("Eco", Color(0xFF26724B)),
    2 to RideModeVisual("Drive", Color(0xFF285FA8)),
    3 to RideModeVisual("Sport", Color(0xFFA13B39))
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    telemetry: DashboardTelemetry,
    featureFlags: FeatureFlags,
    onToggleLock: () -> Unit,
    onFindMe: (Boolean) -> Unit,
    onToggleHeadlight: () -> Unit,
    onToggleCruise: () -> Unit,
    onToggleStartupMode: () -> Unit,
    onSetGear: (Int) -> Unit,
    scooterName: String,
    isDemoMode: Boolean,
    onOpenSettings: () -> Unit,
    onOpenCompartments: () -> Unit,
    onOpenAmbientLight: () -> Unit,
    onOpenFeatureSupport: () -> Unit,
    onOpenDebug: () -> Unit,
    onDisconnect: () -> Unit
) {
    val anyCompartment = featureFlags.seatLock || featureFlags.helmetLock ||
        featureFlags.storageLock || featureFlags.brakeLock
    var overflowExpanded by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            scooterName,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (isDemoMode) {
                            Spacer(Modifier.width(8.dp))
                            PremiumGlassSurface(
                                shape = CircleShape,
                                role = PremiumGlassRole.STATUS,
                                selectedTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            ) {
                                Text(
                                    "DEMO",
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (featureFlags.ambientLight) {
                        PremiumGlassIconButton(onClick = onOpenAmbientLight) {
                            Icon(Icons.Filled.Lightbulb, contentDescription = "Ambient lighting")
                        }
                    }
                    if (anyCompartment) {
                        PremiumGlassIconButton(onClick = onOpenCompartments) {
                            Icon(Icons.Filled.Inventory2, contentDescription = "Compartments")
                        }
                    }
                    PremiumGlassIconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    Box {
                        PremiumGlassIconButton(onClick = { overflowExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = overflowExpanded,
                            onDismissRequest = { overflowExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Feature support") },
                                leadingIcon = { Icon(Icons.Filled.Checklist, contentDescription = null) },
                                onClick = {
                                    overflowExpanded = false
                                    onOpenFeatureSupport()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Debug log") },
                                leadingIcon = { Icon(Icons.Filled.BugReport, contentDescription = null) },
                                onClick = {
                                    overflowExpanded = false
                                    onOpenDebug()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Disconnect") },
                                leadingIcon = { Icon(Icons.Filled.BluetoothDisabled, contentDescription = null) },
                                onClick = {
                                    overflowExpanded = false
                                    onDisconnect()
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val miles = telemetry.speedUnitMiles == true
            SpeedGauge(speedKmh = telemetry.speedKmh, miles = miles)

            Spacer(Modifier.height(20.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatChip(
                    icon = Icons.Filled.BatteryFull,
                    label = telemetry.batteryPercent?.let { "$it%" } ?: "—"
                )
                StatChip(
                    icon = Icons.Filled.Bolt,
                    label = telemetry.voltage?.let { "%.1fV".format(it) } ?: "—"
                )
                StatChip(
                    icon = Icons.Filled.Thermostat,
                    label = telemetry.motorTempC?.let { "${it}°C" } ?: "—"
                )
            }

            if (featureFlags.headlight || featureFlags.cruise || featureFlags.startupMode) {
                Spacer(Modifier.height(28.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (featureFlags.headlight) {
                        QuickToggle(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Filled.Lightbulb,
                            label = "Headlight",
                            on = telemetry.headlightOn == true,
                            activeColor = ActiveAmber,
                            onClick = onToggleHeadlight
                        )
                    }
                    if (featureFlags.cruise) {
                        QuickToggle(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Filled.Speed,
                            label = "Cruise",
                            on = telemetry.cruiseOn == true,
                            activeColor = ActiveBlue,
                            onClick = onToggleCruise
                        )
                    }
                    if (featureFlags.startupMode) {
                        QuickToggle(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Filled.PowerSettingsNew,
                            label = "Kick start",
                            on = telemetry.startupModeOn == true,
                            activeColor = ActiveGreen,
                            onClick = onToggleStartupMode
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            RideModeSelector(
                currentGear = ProtocolCodec.uiGear(telemetry.gear),
                available = telemetry.availableGears,
                onSetGear = onSetGear
            )

            Spacer(Modifier.height(22.dp))

            val locked = telemetry.locked
            val lockColor by animateColorAsState(
                targetValue = if (locked == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                animationSpec = tween(180),
                label = "lockButtonColor"
            )

            PremiumGlassButton(
                onClick = onToggleLock,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                tint = lockColor.copy(alpha = 0.24f),
                contentColor = if (locked == true) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                }
            ) {
                Icon(
                    if (locked == true) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = null
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    when (locked) {
                        true -> "Locked — tap to unlock"
                        false -> "Unlocked — tap to lock"
                        null -> "Lock state unknown — tap to toggle"
                    },
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(12.dp))

            var findMeActive by remember { mutableStateOf(false) }
            PremiumGlassOutlinedButton(
                onClick = {
                    findMeActive = !findMeActive
                    onFindMe(findMeActive)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Icon(Icons.Filled.Campaign, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(if (findMeActive) "Stop find-my-scooter" else "Find my scooter")
            }

            Spacer(Modifier.height(22.dp))

            telemetry.totalDistanceKm?.let { km ->
                val shown = Units.distance(km.toDouble(), miles)
                Text(
                    "Total distance: ${if (miles) "%.1f".format(shown) else km.toString()} ${Units.distanceLabel(miles)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        }
}

@Composable
private fun SpeedGauge(speedKmh: Double?, miles: Boolean) {
    val displaySpeed = speedKmh?.let { Units.speed(it, miles).roundToInt() }

    Box(
        modifier = Modifier.size(220.dp),
        contentAlignment = Alignment.Center
    ) {
        PremiumGlassSurface(
            modifier = Modifier.fillMaxSize(),
            shape = CircleShape,
            role = PremiumGlassRole.TILE,
            border = BorderStroke(
                3.dp,
                MaterialTheme.colorScheme.primary.copy(alpha = 0.66f)
            )
        ) {}
        // The glass edge alone is too subtle over the decorative backdrop. A second inset ring
        // gives the speed readout a stable, high-contrast instrument boundary in every theme.
        Box(
            Modifier
                .size(204.dp)
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.26f),
                    CircleShape
                )
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            RollingSpeedText(displaySpeed)
            Text(
                text = Units.speedLabel(miles),
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Direction-aware numeric transition modelled after SwiftUI's numericText behavior:
 * increasing values roll upward, decreasing values roll downward. We animate the
 * displayed integer rather than tweening every telemetry sample, which keeps fast
 * BLE updates from building a long animation queue.
 */
@Composable
private fun RollingSpeedText(value: Int?) {
    if (value == null) {
        Text(
            text = "—",
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold
        )
        return
    }

    AnimatedContent(
        targetState = value,
        transitionSpec = {
            val increasing = targetState > initialState
            // Translation-only numeric motion avoids two alpha-composited text layers while
            // telemetry is updating. The shorter handoff also prevents rapid speed samples from
            // keeping multiple outgoing numbers alive at once.
            val enter = slideInVertically(
                animationSpec = tween(180),
                initialOffsetY = { height -> if (increasing) height / 2 else -height / 2 }
            )
            val exit = slideOutVertically(
                animationSpec = tween(180),
                targetOffsetY = { height -> if (increasing) -height / 2 else height / 2 }
            )
            enter togetherWith exit
        },
        label = "speedNumericText"
    ) { shown ->
        Text(
            text = shown.toString(),
            style = MaterialTheme.typography.displayLarge,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun StatChip(icon: ImageVector, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun QuickToggle(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    on: Boolean,
    activeColor: Color,
    onClick: () -> Unit
) {
    // The press spring is the interaction animation. Keep state tint changes immediate so a tap
    // does not also recompose the whole blurred tile for several animation frames.
    val bg = if (on) activeColor.copy(alpha = 0.22f) else Color.Transparent
    val fg = if (on) activeColor else MaterialTheme.colorScheme.onSurface

    val interactionSource = remember { MutableInteractionSource() }
    PremiumGlassSurface(
        selectedTint = bg,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, if (on) activeColor else activeColor.copy(alpha = 0.70f)),
        modifier = modifier
            .height(74.dp)
            .premiumPress(interactionSource, pressedScale = 0.945f)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                label,
                color = fg,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun RideModeSelector(
    currentGear: Int?,
    available: Set<Int>?,
    onSetGear: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        for (gear in 1..3) {
            val visual = rideModes.getValue(gear)
            val selected = currentGear == gear
            val enabled = available?.contains(gear) ?: true
            val container = when {
                !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f)
                selected -> visual.accent.copy(alpha = 0.22f)
                else -> Color.Transparent
            }
            val content = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                selected -> visual.accent
                else -> MaterialTheme.colorScheme.onSurface
            }

            val interactionSource = remember(gear) { MutableInteractionSource() }
            PremiumGlassSurface(
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
                    .premiumPress(interactionSource, pressedScale = 0.95f)
                    .clickable(
                        enabled = enabled,
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = { onSetGear(gear) }
                    ),
                shape = RoundedCornerShape(16.dp),
                selectedTint = container,
                border = BorderStroke(
                    1.dp,
                    if (selected) visual.accent else visual.accent.copy(alpha = if (enabled) 0.70f else 0.25f)
                )
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        visual.label,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        color = content
                    )
                }
            }
        }
    }
}
