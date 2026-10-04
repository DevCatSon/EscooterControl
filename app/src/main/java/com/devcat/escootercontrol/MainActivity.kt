package com.devcat.escootercontrol

import android.Manifest
import android.app.Activity
import android.graphics.Color as AndroidColor
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.devcat.escootercontrol.ble.ConnectionState
import com.devcat.escootercontrol.ble.DiscoveredDevice
import com.devcat.escootercontrol.data.AppearanceStore
import com.devcat.escootercontrol.data.FeatureFlags
import com.devcat.escootercontrol.data.ScooterStore
import com.devcat.escootercontrol.data.SetupStore
import com.devcat.escootercontrol.ui.components.PremiumBackdrop
import com.devcat.escootercontrol.ui.components.PremiumMotion
import com.devcat.escootercontrol.ui.components.PremiumNavigationGlowOverlay
import com.devcat.escootercontrol.ui.components.PremiumPageSurface
import com.devcat.escootercontrol.ui.components.ScooterLockedOverlay
import com.devcat.escootercontrol.ui.screens.AmbientLightScreen
import com.devcat.escootercontrol.ui.screens.CompartmentsScreen
import com.devcat.escootercontrol.ui.screens.DashboardScreen
import com.devcat.escootercontrol.ui.screens.DebugScreen
import com.devcat.escootercontrol.ui.screens.FeatureSupportScreen
import com.devcat.escootercontrol.ui.screens.OnboardingScreen
import com.devcat.escootercontrol.ui.screens.ScanScreen
import com.devcat.escootercontrol.ui.screens.ScooterSettingsScreen
import com.devcat.escootercontrol.ui.screens.SensorsScreen
import com.devcat.escootercontrol.ui.screens.SettingsScreen
import com.devcat.escootercontrol.ui.theme.AppThemeMode
import com.devcat.escootercontrol.ui.theme.EscooterControlTheme
import com.devcat.escootercontrol.viewmodel.ScooterViewModel

private object Routes {
    const val SCAN = "scan"
    const val DASHBOARD = "dashboard"
    const val SETTINGS = "settings"
    const val SCOOTER_SETTINGS = "scooter_settings"
    const val SENSORS = "sensors"
    const val COMPARTMENTS = "compartments"
    const val AMBIENT = "ambient"
    const val FEATURES = "features"
    const val DEBUG = "debug"
}

class MainActivity : ComponentActivity() {

    private val viewModel: ScooterViewModel by viewModels()

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasRequiredPermissions(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * Android Studio device mirroring and some desktop-style pointer stacks can emit an
     * ACTION_HOVER_EXIT immediately around a scroll event. Certain Compose input paths can
     * retain a delayed hover-exit callback and then crash when a newer pointer event replaces
     * the stored event.
     *
     * A hover-exit from a mouse carries no app action for this touch-first UI, so consume only
     * that event before it reaches AndroidComposeView. Touch, stylus, buttons and scroll events
     * continue through the normal dispatch path.
     */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val isMouseHoverExit =
            event.actionMasked == MotionEvent.ACTION_HOVER_EXIT &&
                event.isFromSource(InputDevice.SOURCE_MOUSE) &&
                event.pointerCount > 0 &&
                event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE

        if (isMouseHoverExit) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            var hasPermissions by remember { mutableStateOf(hasRequiredPermissions()) }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) {
                hasPermissions = hasRequiredPermissions()
            }

            val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
            val scanResults by viewModel.scanResults.collectAsStateWithLifecycle()
            val scanError by viewModel.scanError.collectAsStateWithLifecycle()
            val featureFlags by viewModel.featureFlags.collectAsStateWithLifecycle()
            val connectedAddress by viewModel.connectedAddress.collectAsStateWithLifecycle()
            val connectedName by viewModel.connectedName.collectAsStateWithLifecycle()
            val nicknames by viewModel.nicknames.collectAsStateWithLifecycle()
            val demoMode by viewModel.demoMode.collectAsStateWithLifecycle()

            val appearanceStore = remember { AppearanceStore(applicationContext) }
            val themeMode by appearanceStore.themeMode.collectAsStateWithLifecycle()
            val setupStore = remember { SetupStore(applicationContext) }
            val setupCompleted by setupStore.completed.collectAsStateWithLifecycle()
            val setupStep by setupStore.step.collectAsStateWithLifecycle()
            val demoDiscovery by setupStore.demoDiscovery.collectAsStateWithLifecycle()

            val navController = rememberNavController()
            val currentBackStackEntry by navController.currentBackStackEntryAsState()
            val currentRoute = currentBackStackEntry?.destination?.route

            LaunchedEffect(hasPermissions, setupCompleted) {
                if (hasPermissions && setupCompleted) viewModel.autoReconnectOnce()
            }

            LaunchedEffect(connectionState, setupCompleted, currentRoute) {
                if (!setupCompleted || currentRoute == null) return@LaunchedEffect
                when (connectionState) {
                    // Only promote the connection flow itself from Scan to Dashboard.
                    // A READY scooter must not force-close child destinations such as
                    // Settings, Sensors, Compartments, or Debug whenever their route changes.
                    ConnectionState.READY -> {
                        if (currentRoute == Routes.SCAN) {
                            navController.navigate(Routes.DASHBOARD) {
                                popUpTo(Routes.SCAN) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                    ConnectionState.DISCONNECTED -> {
                        if (currentRoute != Routes.SCAN) {
                            navController.navigate(Routes.SCAN) {
                                popUpTo(0)
                                launchSingleTop = true
                            }
                        }
                    }
                    else -> Unit
                }
            }

            val snackbarHostState = remember { SnackbarHostState() }
            LaunchedEffect(Unit) {
                viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
            }

            EscooterControlTheme(mode = themeMode) {
                ConfigureSystemBars(themeMode)
                PremiumBackdrop {
                    Box(Modifier.fillMaxSize()) {
                        AnimatedContent(
                            targetState = setupCompleted,
                            transitionSpec = {
                                // Setup/app handoff uses the same opaque spatial model as normal navigation.
                                // No alpha, blur, or scale is animated, so neither page can ghost through.
                                val settle = spring<IntOffset>(
                                    dampingRatio = PremiumMotion.NavigationDamping,
                                    stiffness = PremiumMotion.NavigationStiffness
                                )
                                val forward = targetState
                                val enter = slideInHorizontally(
                                    animationSpec = settle,
                                    initialOffsetX = { width -> if (forward) width else -width }
                                )
                                val exit = slideOutHorizontally(
                                    animationSpec = settle,
                                    targetOffsetX = { width ->
                                        if (forward) -width / PremiumMotion.BackgroundParallaxDivisor
                                        else width / PremiumMotion.BackgroundParallaxDivisor
                                    }
                                )
                                enter togetherWith exit
                            },
                            label = "setupCompletion",
                            modifier = Modifier.fillMaxSize()
                        ) { completed ->
                            if (!completed) {
                                PremiumPageSurface {
                                    OnboardingScreen(
                                        step = setupStep,
                                        hasPermissions = hasPermissions,
                                        connectionState = connectionState,
                                        scanResults = scanResults,
                                        scanError = scanError,
                                        connectedAddress = connectedAddress,
                                        connectedName = connectedName,
                                        nickname = connectedAddress?.let { nicknames[it] },
                                        themeMode = themeMode,
                                        isDemoMode = demoMode,
                                        demoDiscoveryMode = demoDiscovery,
                                        onStepChanged = setupStore::setStep,
                                        onStartDemoDiscovery = setupStore::startDemoDiscovery,
                                        onStopDemoDiscovery = setupStore::stopDemoDiscovery,
                                        onRequestPermissions = { permissionLauncher.launch(requiredPermissions()) },
                                        onStartScan = viewModel::startScan,
                                        onStopScan = viewModel::stopScan,
                                        onConnect = { viewModel.connect(it.device, it.name) },
                                        onCancelConnect = viewModel::cancelConnect,
                                        onEnterDemo = viewModel::enterDemoMode,
                                        onRename = { name -> connectedAddress?.let { viewModel.renameScooter(it, name) } },
                                        onThemeModeChanged = appearanceStore::setThemeMode,
                                        onComplete = setupStore::complete
                                    )
                                }
                            } else {
                                VicontNavHost(
                                    navController = navController,
                                    connectionState = connectionState,
                                    scanResults = scanResults,
                                    scanError = scanError,
                                    featureFlags = featureFlags,
                                    viewModel = viewModel,
                                    themeMode = themeMode,
                                    hasPermissions = hasPermissions,
                                    isDemoMode = demoMode,
                                    onRequestPermissions = { permissionLauncher.launch(requiredPermissions()) },
                                    onThemeModeChanged = appearanceStore::setThemeMode,
                                    onRunSetupAgain = {
                                        viewModel.disconnect()
                                        setupStore.restart()
                                    }
                                )
                            }
                        }

                        SnackbarHost(
                            snackbarHostState,
                            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigureSystemBars(mode: AppThemeMode) {
    val activity = LocalContext.current as? Activity ?: return
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        AppThemeMode.SYSTEM -> systemDark
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK, AppThemeMode.MIDNIGHT -> true
    }

    SideEffect {
        activity.window.statusBarColor = AndroidColor.TRANSPARENT
        activity.window.navigationBarColor = AndroidColor.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            activity.window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
}

@Composable
private fun VicontNavHost(
    navController: NavHostController,
    connectionState: ConnectionState,
    scanResults: List<DiscoveredDevice>,
    scanError: String?,
    featureFlags: FeatureFlags,
    viewModel: ScooterViewModel,
    themeMode: AppThemeMode,
    hasPermissions: Boolean,
    isDemoMode: Boolean,
    onRequestPermissions: () -> Unit,
    onThemeModeChanged: (AppThemeMode) -> Unit,
    onRunSetupAgain: () -> Unit
) {
    val lastScooter by viewModel.lastScooter.collectAsStateWithLifecycle()
    val nicknames by viewModel.nicknames.collectAsStateWithLifecycle()
    val connectedAddress by viewModel.connectedAddress.collectAsStateWithLifecycle()
    val connectedName by viewModel.connectedName.collectAsStateWithLifecycle()
    val locked by viewModel.locked.collectAsStateWithLifecycle()
    val scooterName = ScooterStore.displayName(connectedAddress, connectedName, nicknames)

    // Keep glass rendering visually stable through route motion. Toggling the live blur
    // during a transition changes glass luminance and causes a bright-then-dim flash when
    // sampling resumes. Performance is handled by reduced Haze input sampling and draw-layer
    // transforms instead, so the material never changes modes mid-animation.

    // Navigation stays spatial and cheap: only translation is animated. The foreground
    // enters from the trailing edge while the previous destination moves a short parallax
    // distance. Popping is the exact inverse. No blur, fade, scale, or layout-size animation
    // is attached to route changes, which keeps the compositor workload predictable.
    val navSlideSpec = spring<IntOffset>(
        dampingRatio = PremiumMotion.NavigationDamping,
        stiffness = PremiumMotion.NavigationStiffness
    )
    val forwardDirection = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1

    val initialRoute = remember {
        if (connectionState == ConnectionState.READY) Routes.DASHBOARD else Routes.SCAN
    }

    Box(Modifier.fillMaxSize()) {
        // Full-screen blur is reserved for the locked modal state only. It is never part of
        // route/setup animation. 18dp plus the light scrim below lands in the requested restrained
        // ~15-30% visual-blur range without destroying context behind the lock prompt.
        val navModifier = if (locked == true && connectionState == ConnectionState.READY) {
            Modifier.fillMaxSize().blur(18.dp)
        } else {
            Modifier.fillMaxSize()
        }

        Box(modifier = navModifier) {
            NavHost(
                    navController = navController,
                    startDestination = initialRoute,
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = {
                        slideInHorizontally(
                            animationSpec = navSlideSpec,
                            initialOffsetX = { fullWidth -> fullWidth * forwardDirection }
                        )
                    },
                    exitTransition = {
                        slideOutHorizontally(
                            animationSpec = navSlideSpec,
                            targetOffsetX = { fullWidth ->
                                -fullWidth * forwardDirection / PremiumMotion.BackgroundParallaxDivisor
                            }
                        )
                    },
                    popEnterTransition = {
                        slideInHorizontally(
                            animationSpec = navSlideSpec,
                            initialOffsetX = { fullWidth ->
                                -fullWidth * forwardDirection / PremiumMotion.BackgroundParallaxDivisor
                            }
                        )
                    },
                    popExitTransition = {
                        slideOutHorizontally(
                            animationSpec = navSlideSpec,
                            targetOffsetX = { fullWidth -> fullWidth * forwardDirection }
                        )
                    }
                ) {
                    composable(Routes.SCAN) {
                        PremiumPageSurface {
                            ScanScreen(
                                connectionState = connectionState,
                                scanResults = scanResults,
                                scanError = scanError,
                                onStartScan = viewModel::startScan,
                                onStopScan = viewModel::stopScan,
                                onConnect = { viewModel.connect(it.device, it.name) },
                                lastScooter = lastScooter,
                                nicknames = nicknames,
                                onReconnect = { viewModel.reconnectLast() },
                                onCancelConnect = viewModel::cancelConnect,
                                hasPermissions = hasPermissions,
                                onRequestPermissions = onRequestPermissions,
                                onEnterDemo = { viewModel.enterDemoMode() }
                            )
                        }
                    }
    
                    composable(Routes.DASHBOARD) {
                        val telemetry by viewModel.dashboardTelemetry.collectAsStateWithLifecycle()
                        PremiumPageSurface {
                            DashboardScreen(
                                telemetry = telemetry,
                                featureFlags = featureFlags,
                                onToggleLock = viewModel::toggleLock,
                                onFindMe = viewModel::findMe,
                                onToggleHeadlight = viewModel::toggleHeadlight,
                                onToggleCruise = viewModel::toggleCruise,
                                onToggleStartupMode = viewModel::toggleStartupMode,
                                onSetGear = { viewModel.setGear(it) },
                                scooterName = scooterName,
                                isDemoMode = isDemoMode,
                                onOpenSettings = { navController.navigate(Routes.SCOOTER_SETTINGS) },
                                onOpenCompartments = { navController.navigate(Routes.COMPARTMENTS) },
                                onOpenAmbientLight = { navController.navigate(Routes.AMBIENT) },
                                onOpenFeatureSupport = { navController.navigate(Routes.FEATURES) },
                                onOpenDebug = { navController.navigate(Routes.DEBUG) },
                                onDisconnect = viewModel::disconnect
                            )
                        }
                    }
    
                    composable(Routes.SCOOTER_SETTINGS) {
                        val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
                        PremiumPageSurface {
                            ScooterSettingsScreen(
                                telemetry = telemetry,
                                onBack = { navController.popBackStack() },
                                onSetSpeedUnit = { viewModel.setSpeedUnit(it) },
                                onQueryVersions = viewModel::queryVersions,
                                scooterAddress = connectedAddress,
                                advertisedName = connectedName,
                                nickname = connectedAddress?.let { nicknames[it] },
                                onRename = { name -> connectedAddress?.let { viewModel.renameScooter(it, name) } },
                                onForget = viewModel::forgetSavedScooter,
                                onOpenSensors = { navController.navigate(Routes.SENSORS) },
                                onOpenTuning = { navController.navigate(Routes.SETTINGS) },
                                themeMode = themeMode,
                                onThemeModeChanged = onThemeModeChanged,
                                onRunSetupAgain = onRunSetupAgain,
                                isDemoMode = isDemoMode
                            )
                        }
                    }
    
                    composable(Routes.SENSORS) {
                        val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
                        PremiumPageSurface {
                            SensorsScreen(
                                telemetry = telemetry,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
    
                    composable(Routes.SETTINGS) {
                        val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
                        PremiumPageSurface {
                            SettingsScreen(
                                telemetry = telemetry,
                                onBack = { navController.popBackStack() },
                                onQueryConfig = viewModel::queryCurrentConfig,
                                onSetMaxSpeed = { viewModel.setMaxSpeed(it) },
                                onSetStartingTorque = { viewModel.setStartingTorque(it) },
                                onSetMaxTorque = { viewModel.setMaxDrivingTorque(it) },
                                onSetBrakeStrength = { viewModel.setBrakeStrength(it) },
                                onResetDefaults = viewModel::resetTuningToDefaults,
                                useMiles = telemetry.speedUnitMiles == true
                            )
                        }
                    }
    
                    composable(Routes.COMPARTMENTS) {
                        PremiumPageSurface {
                            CompartmentsScreen(
                                featureFlags = featureFlags,
                                onBack = { navController.popBackStack() },
                                onPulseSeat = viewModel::pulseSeatLock,
                                onPulseHelmet = viewModel::pulseHelmetLock,
                                onPulseStorage = viewModel::pulseStorageLock,
                                onPulseBrake = viewModel::pulseBrakeLock
                            )
                        }
                    }
    
                    composable(Routes.AMBIENT) {
                        PremiumPageSurface {
                            AmbientLightScreen(
                                onBack = { navController.popBackStack() },
                                onSetMode = { mode, color -> viewModel.setAmbientLampMode(mode, color) }
                            )
                        }
                    }
    
                    composable(Routes.DEBUG) {
                        val telemetry by viewModel.telemetry.collectAsStateWithLifecycle()
                        PremiumPageSurface {
                            DebugScreen(
                                log = viewModel.frameLog,
                                telemetry = telemetry,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
    
                    composable(Routes.FEATURES) {
                        PremiumPageSurface {
                            FeatureSupportScreen(
                                flags = featureFlags,
                                onBack = { navController.popBackStack() },
                                onUpdate = viewModel::setFeatureSupported
                            )
                        }
                    }
                }

            // Decorative lighting is viewport-stable instead of route-owned, so it no longer
            // rides an outgoing page toward the corner and suddenly vanishes at transition end.
            PremiumNavigationGlowOverlay()
        }

        ScooterLockedOverlay(
            visible = locked == true && connectionState == ConnectionState.READY,
            onUnlock = viewModel::toggleLock
        )
    }
}
