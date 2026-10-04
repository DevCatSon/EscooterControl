package com.devcat.escootercontrol.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import com.devcat.escootercontrol.ui.components.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.devcat.escootercontrol.ble.ConnectionState
import com.devcat.escootercontrol.ble.DiscoveredDevice
import com.devcat.escootercontrol.data.ScooterStore
import com.devcat.escootercontrol.data.SetupStep
import com.devcat.escootercontrol.ui.theme.AppThemeMode

private data class DemoDiscoveryDevice(
    val name: String,
    val rssi: Int,
    val detail: String
)

private val demoDiscoveryDevices = listOf(
    DemoDiscoveryDevice("VC-Demo 01", -43, "Strong signal · simulated"),
    DemoDiscoveryDevice("E-S Demo", -57, "Good signal · simulated"),
    DemoDiscoveryDevice("SN-Demo 03", -71, "Nearby · simulated")
)

@Composable
fun OnboardingScreen(
    step: SetupStep,
    hasPermissions: Boolean,
    connectionState: ConnectionState,
    scanResults: List<DiscoveredDevice>,
    scanError: String?,
    connectedAddress: String?,
    connectedName: String?,
    nickname: String?,
    themeMode: AppThemeMode,
    isDemoMode: Boolean,
    demoDiscoveryMode: Boolean,
    onStepChanged: (SetupStep) -> Unit,
    onStartDemoDiscovery: () -> Unit,
    onStopDemoDiscovery: () -> Unit,
    onRequestPermissions: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (DiscoveredDevice) -> Unit,
    onCancelConnect: () -> Unit,
    onEnterDemo: (String) -> Unit,
    onRename: (String) -> Unit,
    onThemeModeChanged: (AppThemeMode) -> Unit,
    onComplete: () -> Unit
) {
    val transition = spring<IntOffset>(
        dampingRatio = PremiumMotion.NavigationDamping,
        stiffness = PremiumMotion.NavigationStiffness
    )
    val goBack: () -> Unit = {
        when (step) {
            SetupStep.WELCOME -> Unit
            SetupStep.BLUETOOTH -> onStepChanged(SetupStep.WELCOME)
            SetupStep.PAIR -> {
                onStopScan()
                if (connectionState == ConnectionState.READY) onCancelConnect()
                if (demoDiscoveryMode) {
                    onStopDemoDiscovery()
                    onStepChanged(SetupStep.WELCOME)
                } else {
                    onStepChanged(SetupStep.BLUETOOTH)
                }
            }
            SetupStep.NAME -> {
                if (isDemoMode) onCancelConnect()
                onStepChanged(SetupStep.PAIR)
            }
            SetupStep.APPEARANCE -> {
                onStepChanged(if (connectionState == ConnectionState.READY) SetupStep.NAME else SetupStep.PAIR)
            }
            SetupStep.READY -> onStepChanged(SetupStep.APPEARANCE)
        }
    }
    BackHandler(enabled = step != SetupStep.WELCOME, onBack = goBack)

    // Pairing is now mandatory for a real scooter. Demo Mode is the offline path.
    // This also migrates an interrupted setup from older builds that allowed skipping pairing.
    LaunchedEffect(step, connectionState) {
        if (step >= SetupStep.NAME && step != SetupStep.WELCOME && connectionState != ConnectionState.READY) {
            onStepChanged(SetupStep.PAIR)
        }
    }

    // Setup keeps the same hard, opaque page background as the rest of the app, but its
    // compact glass controls intentionally use a much stronger 40dp material blur.
    PremiumGlassStrength(blurRadius = 40.dp) {
        Scaffold(
            // Setup uses one hard background from the status bar through the footer.
            // Repainting the decorative gradient inside each animated step used a different
            // coordinate origin and created the visible horizontal seam under the top bar.
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            topBar = {
                if (step != SetupStep.WELCOME) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = goBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Back")
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            "Setup",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                }
            },
            bottomBar = {
                SetupProgress(step = step)
            }
        ) { padding ->
            // Keep setup glass in the same rendering mode for the entire handoff. The outgoing
            // page still gets the intentional fixed 40dp depth blur below, while compact glass
            // controls remain visually stable instead of flashing when live sampling resumes.
            AnimatedContent(
                    targetState = step,
                    transitionSpec = {
                        val forward = targetState.ordinal >= initialState.ordinal
                        val enter = slideInHorizontally(
                            animationSpec = transition,
                            initialOffsetX = { if (forward) it else -it }
                        )
                        val exit = slideOutHorizontally(
                            animationSpec = transition,
                            targetOffsetX = {
                                if (forward) -it / PremiumMotion.BackgroundParallaxDivisor
                                else it / PremiumMotion.BackgroundParallaxDivisor
                            }
                        )
                        enter togetherWith exit
                    },
                    label = "setupStep",
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        // Keep animated setup pages inside the content viewport so text/cards can
                        // never draw over the fixed top bar or setup progress bar while sliding.
                        .clipToBounds()
                ) { current ->
                    // Setup deliberately uses a stronger handoff than the normal app navigation.
                    // As soon as a step becomes the outgoing page, blur that entire page to 40dp.
                    // The incoming destination stays sharp, which prevents two readable screens from
                    // visually stacking while the spatial slide finishes. A fixed radius is cheaper
                    // than animating the blur kernel every frame.
                    val outgoing = current != step
                    Box(
                        Modifier
                            .fillMaxSize()
                            // Match Scaffold exactly so the moving page and fixed top/bottom bars
                            // stay visually continuous during and after the transition.
                            .background(MaterialTheme.colorScheme.background)
                            .then(if (outgoing) Modifier.blur(40.dp) else Modifier)
                    ) {
                        when (current) {
                            SetupStep.WELCOME -> WelcomeStep(
                                onContinue = { onStepChanged(SetupStep.BLUETOOTH) },
                                onDemo = {
                                    onStartDemoDiscovery()
                                    onStepChanged(SetupStep.PAIR)
                                }
                            )

                            SetupStep.BLUETOOTH -> BluetoothStep(
                                hasPermissions = hasPermissions,
                                onRequestPermissions = onRequestPermissions,
                                onContinue = { onStepChanged(SetupStep.PAIR) }
                            )

                            SetupStep.PAIR -> PairStep(
                                hasPermissions = hasPermissions,
                                demoDiscoveryMode = demoDiscoveryMode,
                                connectionState = connectionState,
                                scanResults = scanResults,
                                scanError = scanError,
                                connectedName = connectedName,
                                onRequestPermissions = onRequestPermissions,
                                onStartScan = onStartScan,
                                onStopScan = onStopScan,
                                onConnect = onConnect,
                                onEnterDemo = onEnterDemo,
                                onCancelConnect = onCancelConnect,
                                onContinue = { onStepChanged(SetupStep.NAME) }
                            )

                            SetupStep.NAME -> NameStep(
                                advertisedName = connectedName,
                                nickname = nickname,
                                isDemoMode = isDemoMode,
                                onRename = onRename,
                                onContinue = { onStepChanged(SetupStep.APPEARANCE) }
                            )

                            SetupStep.APPEARANCE -> AppearanceStep(
                                themeMode = themeMode,
                                onThemeModeChanged = onThemeModeChanged,
                                onContinue = { onStepChanged(SetupStep.READY) }
                            )

                            SetupStep.READY -> ReadyStep(
                                connected = connectionState == ConnectionState.READY,
                                scooterName = ScooterStore.displayName(
                                    connectedAddress,
                                    connectedName,
                                    nickname?.let { mapOf(connectedAddress.orEmpty() to it) } ?: emptyMap()
                                ),
                                isDemoMode = isDemoMode,
                                themeMode = themeMode,
                                onComplete = onComplete
                            )
                        }
                    }
                }
        }
    }
}

@Composable
private fun SetupProgress(step: SetupStep) {
    val progress = (step.ordinal + 1f) / SetupStep.entries.size.toFloat()
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        LinearProgressIndicator(
            progress = progress,
            modifier = Modifier.fillMaxWidth().height(3.dp),
            trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Setup ${step.ordinal + 1} of ${SetupStep.entries.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SetupPage(
    icon: ImageVector,
    eyebrow: String,
    title: String,
    body: String,
    content: @Composable ColumnScope.() -> Unit = {},
    actions: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.82f),
                modifier = Modifier.size(68.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.height(30.dp))
            Text(
                eyebrow.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                title,
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 44.sp
                )
            )
            Spacer(Modifier.height(14.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(28.dp))
            content()
            Spacer(Modifier.height(18.dp))
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            actions()
        }
    }
}

@Composable
private fun WelcomeStep(onContinue: () -> Unit, onDemo: () -> Unit) {
    SetupPage(
        icon = Icons.Filled.DirectionsBike,
        eyebrow = "Welcome",
        title = "Your scooter, beautifully simple.",
        body = "Connect, personalize and control your scooter from one calm dashboard. Setup takes about a minute.",
        content = {
            FeaturePill(Icons.Filled.Shield, "Local-first", "Scooter names and setup preferences stay on this phone.")
            Spacer(Modifier.height(10.dp))
            FeaturePill(Icons.Filled.WifiTethering, "Direct Bluetooth", "The app talks directly to the scooter; no account is required.")
        },
        actions = {
            PremiumGlassButton(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Get started") }
            TextButton(onClick = onDemo, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Explore with Demo Mode")
            }
        }
    )
}

@Composable
private fun BluetoothStep(
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
    onContinue: () -> Unit
) {
    SetupPage(
        icon = if (hasPermissions) Icons.Filled.Check else Icons.Filled.Bluetooth,
        eyebrow = "Bluetooth",
        title = if (hasPermissions) "Bluetooth is ready." else "Connect only when you choose.",
        body = if (hasPermissions) {
            "Permission is enabled. The next step will look for nearby supported scooters."
        } else {
            "Bluetooth access is needed to discover and connect to your scooter. On older Android versions the system may call this Location permission; the app does not need your location history."
        },
        content = {
            PremiumGlassSurface(
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(14.dp))
                    Column {
                        Text("Permission stays in your control", fontWeight = FontWeight.SemiBold)
                        Text(
                            "You can change it later in Android Settings.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        actions = {
            if (!hasPermissions) {
                PremiumGlassButton(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Allow Bluetooth access")
                }
            } else {
                PremiumGlassButton(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Continue") }
            }
        }
    )
}

@Composable
private fun PairStep(
    hasPermissions: Boolean,
    demoDiscoveryMode: Boolean,
    connectionState: ConnectionState,
    scanResults: List<DiscoveredDevice>,
    scanError: String?,
    connectedName: String?,
    onRequestPermissions: () -> Unit,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onConnect: (DiscoveredDevice) -> Unit,
    onEnterDemo: (String) -> Unit,
    onCancelConnect: () -> Unit,
    onContinue: () -> Unit
) {
    val scanning = !demoDiscoveryMode && connectionState == ConnectionState.SCANNING
    val connecting = !demoDiscoveryMode && (
        connectionState == ConnectionState.CONNECTING || connectionState == ConnectionState.DISCOVERING_SERVICES
    )
    val ready = connectionState == ConnectionState.READY

    var autoScanStarted by remember { mutableStateOf(false) }
    LaunchedEffect(hasPermissions, demoDiscoveryMode) {
        if (!demoDiscoveryMode && hasPermissions && !autoScanStarted && connectionState == ConnectionState.DISCONNECTED) {
            autoScanStarted = true
            onStartScan()
        }
    }

    SetupPage(
        icon = if (ready) Icons.Filled.Check else Icons.Filled.Bluetooth,
        eyebrow = if (demoDiscoveryMode) "Demo pairing" else "Pair",
        title = when {
            ready -> "Scooter connected."
            demoDiscoveryMode -> "Choose a demo scooter."
            else -> "Bring your scooter nearby."
        },
        body = when {
            ready && demoDiscoveryMode -> "${connectedName ?: "Your demo scooter"} is connected locally. Nothing is being sent over Bluetooth."
            ready -> "${connectedName ?: "Your scooter"} is ready to personalize."
            demoDiscoveryMode -> "This simulates the discovery experience with fake nearby devices, signal strength and pairing. Pick one to continue."
            connecting -> "Making a secure local Bluetooth connection…"
            scanning -> "Looking for supported scooters around you. Keep the scooter powered on and close to your phone."
            else -> "We'll scan only while this screen is open."
        },
        content = {
            when {
                ready -> ConnectedCard(connectedName ?: "Scooter", simulated = demoDiscoveryMode)
                demoDiscoveryMode -> DemoDiscoveryList(onEnterDemo)
                !hasPermissions -> {
                    PremiumGlassTonalButton(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                        Text("Grant Bluetooth permission")
                    }
                }
                connecting -> {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.size(14.dp))
                        Text("Connecting…", modifier = Modifier.weight(1f))
                        TextButton(onClick = onCancelConnect) { Text("Cancel") }
                    }
                }
                scanResults.isEmpty() -> {
                    PremiumGlassSurface(
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            Modifier.padding(22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            if (scanning) CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                            else Icon(Icons.Filled.Bluetooth, contentDescription = null, modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(if (scanning) "Searching nearby…" else "No scooter found yet", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Make sure the scooter is awake and not connected to another phone.",
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 230.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(scanResults, key = { it.device.address }) { device ->
                            DeviceCard(
                                name = device.name,
                                detail = "Signal ${device.rssi} dBm",
                                action = "Connect",
                                onClick = { onConnect(device) }
                            )
                        }
                    }
                }
            }

            if (!demoDiscoveryMode && scanError != null) {
                Spacer(Modifier.height(10.dp))
                Text(scanError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        },
        actions = {
            when {
                ready -> PremiumGlassButton(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Continue")
                }
                hasPermissions && !connecting -> PremiumGlassOutlinedButton(
                    onClick = { if (scanning) onStopScan() else onStartScan() },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text(if (scanning) "Stop searching" else "Search again") }
            }
        }
    )
}

@Composable
private fun DemoDiscoveryList(onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        demoDiscoveryDevices.forEach { device ->
            DeviceCard(
                name = device.name,
                detail = "${device.detail} · ${device.rssi} dBm",
                action = "Pair demo",
                onClick = { onSelect(device.name) }
            )
        }
    }
}

@Composable
private fun DeviceCard(
    name: String,
    detail: String,
    action: String,
    onClick: () -> Unit
) {
    PremiumGlassCard(
        onClick = onClick
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    Icons.Filled.DirectionsBike,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(10.dp)
                )
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(action, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ConnectedCard(name: String, simulated: Boolean = false) {
    PremiumGlassSurface(
        shape = RoundedCornerShape(24.dp),
        role = PremiumGlassRole.STATUS,
        selectedTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(9.dp))
            }
            Spacer(Modifier.size(14.dp))
            Column {
                Text(name, fontWeight = FontWeight.SemiBold)
                Text(if (simulated) "Simulated connection · no Bluetooth" else "Connected and ready", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NameStep(
    advertisedName: String?,
    nickname: String?,
    isDemoMode: Boolean,
    onRename: (String) -> Unit,
    onContinue: () -> Unit
) {
    var text by remember(nickname, advertisedName) { mutableStateOf(nickname.orEmpty()) }

    SetupPage(
        icon = Icons.Filled.DirectionsBike,
        eyebrow = "Personalize",
        title = "Make it yours.",
        body = if (isDemoMode) {
            "Give the demo scooter a name. It behaves like a real session, but no Bluetooth commands are sent."
        } else {
            "Choose the name you'll see throughout the app. This nickname is local to this phone and does not rename the scooter over Bluetooth."
        },
        content = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(ScooterStore.MAX_NICKNAME) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Scooter name") },
                placeholder = { Text(advertisedName ?: "My Scooter") }
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "You can change this anytime in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        actions = {
            PremiumGlassButton(
                onClick = {
                    if (text.isNotBlank()) onRename(text)
                    onContinue()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Text(if (text.isBlank()) "Keep ${advertisedName ?: "Scooter"}" else "Save name")
            }
        }
    )
}

@Composable
private fun AppearanceStep(
    themeMode: AppThemeMode,
    onThemeModeChanged: (AppThemeMode) -> Unit,
    onContinue: () -> Unit
) {
    SetupPage(
        icon = Icons.Filled.Palette,
        eyebrow = "Appearance",
        title = "Choose your look.",
        body = "Pick a starting theme. System follows your phone and can use Android dynamic color on supported devices.",
        content = {
            ThemeRow(AppThemeMode.SYSTEM, AppThemeMode.LIGHT, themeMode, onThemeModeChanged)
            Spacer(Modifier.height(10.dp))
            ThemeRow(AppThemeMode.DARK, AppThemeMode.MIDNIGHT, themeMode, onThemeModeChanged)
            Spacer(Modifier.height(12.dp))
            AnimatedVisibility(visible = themeMode == AppThemeMode.MIDNIGHT) {
                Text(
                    "Midnight uses a deep blue-black palette with reduced glare for low-light riding.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        actions = {
            PremiumGlassButton(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Continue") }
        }
    )
}

@Composable
private fun ThemeRow(
    left: AppThemeMode,
    right: AppThemeMode,
    selected: AppThemeMode,
    onSelected: (AppThemeMode) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ThemeCard(left, selected == left, onSelected, Modifier.weight(1f))
        ThemeCard(right, selected == right, onSelected, Modifier.weight(1f))
    }
}

@Composable
private fun ThemeCard(
    mode: AppThemeMode,
    selected: Boolean,
    onSelected: (AppThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(22.dp)
    PremiumGlassSurface(
        onClick = { onSelected(mode) },
        modifier = modifier.aspectRatio(1.55f),
        shape = shape,
        selectedTint = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
        // Every appearance option needs a real boundary. Previously unselected cards had no
        // border at all, which made Light/System/Dark choices disappear into lighter surfaces.
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.90f)
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.62f)
            }
        )
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(mode.displayName, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (selected) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                val tones = when (mode) {
                    AppThemeMode.SYSTEM -> listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.surfaceVariant)
                    AppThemeMode.LIGHT -> listOf(Color(0xFF4E6FAE), Color(0xFFDCE6FF), Color(0xFFF6F7FA))
                    AppThemeMode.DARK -> listOf(Color(0xFFB4C7FF), Color(0xFF30333A), Color(0xFF111318))
                    AppThemeMode.MIDNIGHT -> listOf(Color(0xFF8FB4FF), Color(0xFF15233A), Color(0xFF060B14))
                }
                tones.forEach { tone ->
                    Box(Modifier.size(18.dp).background(tone, CircleShape))
                }
            }
        }
    }
}

@Composable
private fun ReadyStep(
    connected: Boolean,
    scooterName: String,
    isDemoMode: Boolean,
    themeMode: AppThemeMode,
    onComplete: () -> Unit
) {
    SetupPage(
        icon = Icons.Filled.Check,
        eyebrow = "Ready",
        title = "You're all set.",
        body = when {
            isDemoMode -> "Demo Mode is ready. Explore every screen and control without sending anything over Bluetooth."
            connected -> "$scooterName is connected and ready to go."
            else -> "Choose a scooter to finish setup."
        },
        content = {
            SummaryRow("Scooter", if (connected) scooterName else "Not connected")
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SummaryRow("Appearance", themeMode.displayName)
            if (isDemoMode) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                SummaryRow("Session", "Demo — simulated data")
            }
        },
        actions = {
            PremiumGlassButton(onClick = onComplete, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Open dashboard")
            }
        }
    )
}

@Composable
private fun FeaturePill(icon: ImageVector, title: String, body: String) {
    PremiumGlassSurface(
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(14.dp))
            Column {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

private val AppThemeMode.displayName: String
    get() = when (this) {
        AppThemeMode.SYSTEM -> "System"
        AppThemeMode.LIGHT -> "Light"
        AppThemeMode.DARK -> "Dark"
        AppThemeMode.MIDNIGHT -> "Midnight"
    }
