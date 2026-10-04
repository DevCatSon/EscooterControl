package com.devcat.escootercontrol.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.devcat.escootercontrol.ui.components.*
import com.devcat.escootercontrol.ble.ProtocolCodec

private data class LampMode(val id: Int, val label: String)
private val LAMP_MODES = listOf(
    LampMode(1, "Off"),
    LampMode(2, "Horse pattern"),
    LampMode(3, "Banner pattern"),
    LampMode(4, "Purity (solid)"),
    LampMode(5, "Custom color")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmbientLightScreen(
    onBack: () -> Unit,
    onSetMode: (mode: Int, color: ProtocolCodec.AmbientColor?) -> Unit
) {
    var selectedMode by remember { mutableIntStateOf(1) }
    var selectedColor by remember { mutableStateOf(ProtocolCodec.SAFE_AMBIENT_COLORS.first()) }

    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text("Ambient lighting") },
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
            Text("Mode", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            LAMP_MODES.forEach { mode ->
                val selected = selectedMode == mode.id
                PremiumGlassSurface(
                    onClick = {
                        selectedMode = mode.id
                        onSetMode(mode.id, if (mode.id == 5) selectedColor else null)
                    },
                    shape = RoundedCornerShape(16.dp),
                    selectedTint = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
                    border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)) else null,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            mode.label,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (selected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            if (selectedMode == 5) {
                Spacer(Modifier.height(24.dp))
                Text("Color", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Only presets confirmed to fit the protocol's single-byte hue " +
                        "field are shown -- red and purple are excluded until verified " +
                        "(see protocol spec, CODE 65).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ProtocolCodec.SAFE_AMBIENT_COLORS.forEach { color ->
                        val isSelected = selectedColor == color
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color(android.graphics.Color.parseColor(color.hex)))
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape
                                )
                                .clickable {
                                    selectedColor = color
                                    onSetMode(5, color)
                                }
                        )
                    }
                }
            }
        }
    }
}
