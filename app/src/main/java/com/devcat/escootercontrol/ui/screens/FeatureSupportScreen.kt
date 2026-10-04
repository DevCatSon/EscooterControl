package com.devcat.escootercontrol.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devcat.escootercontrol.ui.components.*
import com.devcat.escootercontrol.data.FeatureFlags

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureSupportScreen(
    flags: FeatureFlags,
    onBack: () -> Unit,
    onUpdate: ((FeatureFlags) -> FeatureFlags) -> Unit
) {
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text("Feature support") },
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
                Row(modifier = Modifier.padding(16.dp)) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "The stock app hides buttons your specific scooter model " +
                            "doesn't support using data from Vicont's cloud -- this app " +
                            "doesn't depend on their servers, so turn off anything below " +
                            "that beeps but doesn't actually do anything on your unit. " +
                            "It'll disappear from the dashboard and compartments/lighting " +
                            "screens.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            FeatureRow("Headlight toggle", flags.headlight) { on -> onUpdate { it.copy(headlight = on) } }
            FeatureRow("Cruise control toggle", flags.cruise) { on -> onUpdate { it.copy(cruise = on) } }
            FeatureRow("Startup mode (\"kick start\")", flags.startupMode) { on -> onUpdate { it.copy(startupMode = on) } }
            FeatureRow("Ambient lighting", flags.ambientLight) { on -> onUpdate { it.copy(ambientLight = on) } }
            FeatureRow("Seat / cabin lock", flags.seatLock) { on -> onUpdate { it.copy(seatLock = on) } }
            FeatureRow("Helmet box lock", flags.helmetLock) { on -> onUpdate { it.copy(helmetLock = on) } }
            FeatureRow("Storage basket lock", flags.storageLock) { on -> onUpdate { it.copy(storageLock = on) } }
            FeatureRow("Brake lock", flags.brakeLock) { on -> onUpdate { it.copy(brakeLock = on) } }
        }
    }
}

@Composable
private fun FeatureRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
