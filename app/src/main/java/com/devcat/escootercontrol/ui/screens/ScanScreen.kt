package com.devcat.escootercontrol.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devcat.escootercontrol.ui.components.*
import com.devcat.escootercontrol.ble.ConnectionState
import com.devcat.escootercontrol.ble.DiscoveredDevice
import com.devcat.escootercontrol.data.SavedScooter
import com.devcat.escootercontrol.data.ScooterStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    connectionState: ConnectionState,
    scanResults: List<DiscoveredDevice>,
    scanError: String?,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (DiscoveredDevice) -> Unit,
    lastScooter: SavedScooter?,
    nicknames: Map<String, String>,
    onReconnect: () -> Unit,
    onCancelConnect: () -> Unit,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    onEnterDemo: () -> Unit
) {
    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text("Find your scooter") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val connecting = connectionState == ConnectionState.CONNECTING ||
                connectionState == ConnectionState.DISCOVERING_SERVICES

            if (connecting) {
                PremiumGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(16.dp))
                        Text("Connecting\u2026", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = onCancelConnect) { Text("Cancel") }
                    }
                }
            } else if (lastScooter != null) {
                PremiumGlassCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Last scooter", style = MaterialTheme.typography.labelMedium)
                            Text(
                                ScooterStore.displayName(lastScooter.address, lastScooter.name, nicknames),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                        PremiumGlassTonalButton(onClick = onReconnect) { Text("Connect") }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            if (!hasPermissions) {
                PremiumGlassSurface(
                    role = PremiumGlassRole.STATUS,
                    selectedTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Bluetooth access is off", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Grant permission to scan for a scooter, or open Demo Mode without Bluetooth.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        PremiumGlassTonalButton(onClick = onRequestPermissions) { Text("Grant access") }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }

            val scanning = connectionState == ConnectionState.SCANNING

            Icon(
                imageVector = Icons.Filled.BluetoothSearching,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (scanning) "Scanning for nearby scooters…" else "Not scanning",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(16.dp))

            PremiumGlassButton(
                onClick = { if (scanning) onStopScan() else onStartScan() },
                enabled = hasPermissions
            ) {
                Text(if (scanning) "Stop scan" else "Start scan")
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onEnterDemo) { Text("Explore Demo Mode") }

            if (scanError != null) {
                Spacer(Modifier.height(16.dp))
                PremiumGlassSurface(
                    role = PremiumGlassRole.STATUS,
                    selectedTint = MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(16.dp)) {
                        Icon(
                            Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            scanError,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            if (scanResults.isEmpty()) {
                Text(
                    "No devices found yet. Make sure the scooter is powered on and nearby.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(scanResults, key = { it.device.address }) { device ->
                        ListItem(
                            headlineContent = { Text(ScooterStore.displayName(device.device.address, device.name, nicknames)) },
                            supportingContent = {
                                val renamed = nicknames[device.device.address] != null
                                Text((if (renamed) "${device.name}  •  " else "") + "${device.device.address}  •  RSSI ${device.rssi}")
                            },
                            leadingContent = {
                                Icon(Icons.Filled.DirectionsBike, contentDescription = null)
                            },
                            trailingContent = {
                                PremiumGlassTonalButton(onClick = { onConnect(device) }) {
                                    Text("Connect")
                                }
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }

            if (connectionState == ConnectionState.CONNECTING ||
                connectionState == ConnectionState.DISCOVERING_SERVICES
            ) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Connecting…")
            }
        }
    }
}
