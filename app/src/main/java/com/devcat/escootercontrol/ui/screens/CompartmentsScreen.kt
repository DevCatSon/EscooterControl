package com.devcat.escootercontrol.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.devcat.escootercontrol.ui.components.*
import com.devcat.escootercontrol.data.FeatureFlags

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompartmentsScreen(
    featureFlags: FeatureFlags,
    onBack: () -> Unit,
    onPulseSeat: () -> Unit,
    onPulseHelmet: () -> Unit,
    onPulseStorage: () -> Unit,
    onPulseBrake: () -> Unit
) {
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text("Compartments") },
                navigationIcon = {
                    PremiumGlassIconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(24.dp)
        ) {
            PremiumGlassSurface(
                role = PremiumGlassRole.STATUS,
                selectedTint = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "These likely act as momentary release triggers, not a real " +
                            "persistent lock -- there's no telemetry confirming actual " +
                            "compartment state. Test carefully.",
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            val items = buildList {
                if (featureFlags.seatLock) add(Triple("Seat / cabin", Icons.Filled.EventSeat, onPulseSeat))
                if (featureFlags.helmetLock) add(Triple("Helmet box", Icons.Filled.SportsMotorsports, onPulseHelmet))
                if (featureFlags.storageLock) add(Triple("Storage basket", Icons.Filled.ShoppingBasket, onPulseStorage))
                if (featureFlags.brakeLock) add(Triple("Brake lock", Icons.Filled.Block, onPulseBrake))
            }

            if (items.isEmpty()) {
                Text(
                    "All compartment controls are marked unsupported. Change that in " +
                        "Feature support from the dashboard.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items.forEach { (label, icon, action) ->
                PulseButton(label = label, icon = icon, onClick = action)
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun PulseButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    PremiumGlassButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(72.dp),
        tint = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(16.dp))
        Text(label)
    }
}
