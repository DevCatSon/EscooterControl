package com.devcat.escootercontrol.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.devcat.escootercontrol.ui.components.*
import com.devcat.escootercontrol.ble.Units
import kotlin.math.roundToInt
import com.devcat.escootercontrol.ble.ScooterTelemetry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    telemetry: ScooterTelemetry,
    onBack: () -> Unit,
    onQueryConfig: () -> Unit,
    onSetMaxSpeed: (Int) -> Unit,
    onSetStartingTorque: (Int) -> Unit,
    onSetMaxTorque: (Int) -> Unit,
    onSetBrakeStrength: (Int) -> Unit,
    onResetDefaults: () -> Unit,
    useMiles: Boolean = false
) {
    LaunchedEffect(Unit) { onQueryConfig() }

    var maxSpeed by remember { mutableFloatStateOf(25f) }
    var startTorque by remember { mutableFloatStateOf(5f) }
    var maxTorque by remember { mutableFloatStateOf(5f) }
    var brakeStrength by remember { mutableFloatStateOf(5f) }
    var haveLiveMaxSpeed by remember { mutableStateOf(false) }
    var haveLiveStartTorque by remember { mutableStateOf(false) }
    var haveLiveMaxTorque by remember { mutableStateOf(false) }
    var haveLiveBrakeStrength by remember { mutableStateOf(false) }

    var reportedSpeed by remember { mutableStateOf<Int?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }


    LaunchedEffect(telemetry.configRevision) {
        telemetry.maxSpeedLimit?.let { raw ->
            haveLiveMaxSpeed = true
            if (!dragging) {
                reportedSpeed = raw
                if (raw < 99) maxSpeed = raw.coerceIn(1, 31).toFloat()
            }
        }
        if (!dragging) {
            telemetry.startingTorque?.let { startTorque = it.coerceIn(1, 10).toFloat(); haveLiveStartTorque = true }
            telemetry.maxDrivingTorque?.let { maxTorque = it.coerceIn(1, 10).toFloat(); haveLiveMaxTorque = true }
            telemetry.brakeStrength?.let { brakeStrength = it.coerceIn(0, 9).toFloat(); haveLiveBrakeStrength = true }
        }
    }

    val stillLoading = !(haveLiveMaxSpeed && haveLiveStartTorque && haveLiveMaxTorque && haveLiveBrakeStrength)

    BackHandler(enabled = showResetDialog) { showResetDialog = false }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            modifier = if (showResetDialog) Modifier.blur(16.dp) else Modifier,
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            topBar = {
            TopAppBar(
                title = { Text("Speed & performance") },
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
                .padding(24.dp)
        ) {
            if (stillLoading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Reading current settings from scooter…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            TuningSlider(
                title = "Max speed limit",
                value = maxSpeed,
                valueRange = 1f..31f,
                steps = 29,
                valueLabel = speedLabel(maxSpeed.roundToInt(), reportedSpeed, useMiles),
                onValueChange = { dragging = true; reportedSpeed = null; maxSpeed = it },
                onValueChangeFinished = { dragging = false; onSetMaxSpeed(maxSpeed.roundToInt()) }
            )

            Spacer(Modifier.height(12.dp))

            Text(
                "Quick presets",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            val presets = listOf(6, 15, 20, 25, 31)
            SpeedPresetRow(
                values = presets.take(3),
                selected = reportedSpeed ?: maxSpeed.roundToInt(),
                onSelect = { kmh ->
                    reportedSpeed = null
                    maxSpeed = kmh.toFloat()
                    onSetMaxSpeed(kmh)
                }
            )
            Spacer(Modifier.height(8.dp))
            SpeedPresetRow(
                values = presets.drop(3),
                selected = reportedSpeed ?: maxSpeed.roundToInt(),
                onSelect = { kmh ->
                    reportedSpeed = null
                    maxSpeed = kmh.toFloat()
                    onSetMaxSpeed(kmh)
                }
            )

            Spacer(Modifier.height(32.dp))

            TuningSlider(
                title = "Starting torque",
                value = startTorque,
                valueRange = 1f..10f,
                steps = 8,
                valueLabel = "${startTorque.roundToInt()} / 10",
                onValueChange = { dragging = true; startTorque = it },
                onValueChangeFinished = { dragging = false; onSetStartingTorque(startTorque.roundToInt()) }
            )

            Spacer(Modifier.height(32.dp))

            TuningSlider(
                title = "Max driving torque",
                value = maxTorque,
                valueRange = 1f..10f,
                steps = 8,
                valueLabel = "${maxTorque.roundToInt()} / 10",
                onValueChange = { dragging = true; maxTorque = it },
                onValueChangeFinished = { dragging = false; onSetMaxTorque(maxTorque.roundToInt()) }
            )

            Spacer(Modifier.height(32.dp))

            TuningSlider(
                title = "Electromagnetic brake strength",
                value = brakeStrength,
                valueRange = 0f..9f,
                steps = 8,
                valueLabel = "${brakeStrength.roundToInt()} / 9",
                onValueChange = { dragging = true; brakeStrength = it },
                onValueChangeFinished = { dragging = false; onSetBrakeStrength(brakeStrength.roundToInt()) }
            )

            Spacer(Modifier.height(24.dp))

            PremiumGlassOutlinedButton(
                onClick = { showResetDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Restore tuning defaults") }

            Spacer(Modifier.height(12.dp))

            Text(
                "Each change is confirmed by the scooter; if it doesn't answer, the slider snaps " +
                    "back to the value it really has.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            }
        }

        // Keep the modal composed even while hidden so AnimatedVisibility can play the same
        // exit motion as the lock overlay instead of being removed from composition instantly.
        RestoreDefaultsModal(
            visible = showResetDialog,
            onDismiss = { showResetDialog = false },
            onRestore = {
                showResetDialog = false
                reportedSpeed = null
                onResetDefaults()
            }
        )
    }
}

@Composable
private fun RestoreDefaultsModal(
    visible: Boolean,
    onDismiss: () -> Unit,
    onRestore: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(100f)
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(150)),
            modifier = Modifier.fillMaxSize()
        ) {
            val outsideInteraction = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.22f))
                    .clickable(
                        interactionSource = outsideInteraction,
                        indication = null,
                        onClick = onDismiss
                    )
            )
        }

        // Match the scooter-lock modal exactly: the card drops in with a critically controlled
        // spring, slight scale, and short opacity ramp. Exit is the same restrained reverse.
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(
                animationSpec = spring(
                    dampingRatio = 0.76f,
                    stiffness = 250f
                ),
                initialOffsetY = { -it }
            ) + scaleIn(
                animationSpec = spring(
                    dampingRatio = 0.82f,
                    stiffness = 300f
                ),
                initialScale = 0.88f
            ) + fadeIn(tween(170)),
            exit = slideOutVertically(tween(210)) { -it / 3 } +
                scaleOut(tween(170), targetScale = 0.94f) +
                fadeOut(tween(140)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                PremiumGlassSurface(
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    role = PremiumGlassRole.MODAL,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.50f)
                    ),
                    onClick = {}
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            "Restore tuning defaults?",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            "Resets max speed, starting torque, max torque and brake strength to the scooter's own defaults.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)
                        ) {
                            PremiumGlassOutlinedButton(onClick = onDismiss) {
                                Text("Cancel")
                            }
                            PremiumGlassButton(onClick = onRestore) {
                                Text("Restore")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpeedPresetRow(
    values: List<Int>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        values.forEach { kmh ->
            val isSelected = selected == kmh
            PremiumGlassSurface(
                onClick = { onSelect(kmh) },
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp),
                shape = MaterialTheme.shapes.large,
                selectedTint = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent,
                border = BorderStroke(
                    1.dp,
                    if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.70f)
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.36f)
                )
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        "$kmh km/h",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
        if (values.size == 2) {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun TuningSlider(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(valueLabel, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value,
            valueRange = valueRange,
            steps = steps,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished
        )
    }
}

private fun speedLabel(sliderKmh: Int, reported: Int?, miles: Boolean): String {
    fun fmt(kmh: Int) = if (miles) "$kmh km/h (%.0f mph)".format(Units.speed(kmh.toDouble(), true)) else "$kmh km/h"
    return when {
        reported != null && reported > 31 -> fmt(reported) + " (outside adjustable range)"
        else -> fmt(sliderKmh)
    }
}
