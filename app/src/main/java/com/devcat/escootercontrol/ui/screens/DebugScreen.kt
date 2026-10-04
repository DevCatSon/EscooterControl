package com.devcat.escootercontrol.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devcat.escootercontrol.ui.components.*
import com.devcat.escootercontrol.ble.FrameLog
import com.devcat.escootercontrol.ble.LogDirection
import com.devcat.escootercontrol.ble.FrameLogEvent
import com.devcat.escootercontrol.ble.ScooterTelemetry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live view of every frame sent (TX) and received (RX). If a command "beeps but does nothing",
 * press it, then read here: did the status bit flip afterwards? Copy the log to share it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    log: FrameLog,
    telemetry: ScooterTelemetry,
    onBack: () -> Unit
) {
    val entries = remember(log) { mutableStateListOf<com.devcat.escootercontrol.ble.FrameLogEntry>().apply { addAll(log.snapshot()) } }

    LaunchedEffect(log) {
        log.events.collect { event ->
            when (event) {
                is FrameLogEvent.Added -> {
                    if (entries.size >= log.capacity) entries.removeAt(0)
                    entries.add(event.entry)
                }
                FrameLogEvent.Cleared -> entries.clear()
            }
        }
    }
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val fmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }

    Scaffold(
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        topBar = {
            TopAppBar(
                title = { Text("Debug log") },
                navigationIcon = {
                    PremiumGlassIconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    PremiumGlassIconButton(onClick = { clipboard.setText(AnnotatedString(log.asText())) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy log")
                    }
                    PremiumGlassIconButton(onClick = { log.clear() }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear log")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 12.dp)) {
            Text(
                "Live bits: headlight=${telemetry.headlightOn} cruise=${telemetry.cruiseOn} " +
                    "kick=${telemetry.startupModeOn} locked=${telemetry.locked} " +
                    "bound=${telemetry.bluetoothBound} zt=%02X".format(telemetry.lastZt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(entries) { e ->
                    val color = when (e.direction) {
                        LogDirection.TX -> MaterialTheme.colorScheme.tertiary
                        LogDirection.RX -> MaterialTheme.colorScheme.primary
                        LogDirection.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        "${fmt.format(Date(e.timeMs))} ${e.direction.name.padEnd(4)} ${e.text}",
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}
