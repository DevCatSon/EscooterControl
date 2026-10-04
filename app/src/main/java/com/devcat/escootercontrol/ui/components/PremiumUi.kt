package com.devcat.escootercontrol.ui.components

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** Shared motion values keep navigation and controls feeling coherent. */
object PremiumMotion {
    const val PressInDamping = 1f
    const val PressInStiffness = 1400f
    const val PressReleaseDamping = 0.58f
    const val PressReleaseStiffness = 720f

    // Navigation should read as a spatial relationship, not a spectacle.
    const val NavigationDamping = 0.985f
    const val NavigationStiffness = 850f
    const val BackgroundParallaxDivisor = 7
}

/**
 * Material roles for compact glass surfaces.
 *
 * Blur is deliberately limited to controls and tiles. Full-page/menu backgrounds are never
 * blurred. That keeps visual hierarchy clear and avoids continuously filtering the entire frame.
 */
enum class PremiumGlassRole {
    TILE,
    CONTROL,
    ACCENT_CONTROL,
    STATUS,
    MODAL
}

private val LocalPremiumHazeState = staticCompositionLocalOf<HazeState?> { null }
private val LocalPremiumBlurRadiusOverride = staticCompositionLocalOf<Dp?> { null }

/**
 * Overrides only the blur radius used by compact glass descendants.
 *
 * This does not blur a page, menu, navigation layer, or Scaffold. It is intended for
 * deliberately stronger local treatments such as first-run setup controls.
 */
@Composable
fun PremiumGlassStrength(
    blurRadius: Dp,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalPremiumBlurRadiusOverride provides blurRadius) {
        content()
    }
}

@Composable
private fun glassStyle(role: PremiumGlassRole): HazeStyle {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.background.luminance() < 0.5f

    val (baseBlurRadius, tint, fallback) = when (role) {
        PremiumGlassRole.TILE -> Triple(
            22.dp,
            scheme.surface.copy(alpha = if (dark) 0.50f else 0.58f),
            scheme.surface.copy(alpha = if (dark) 0.92f else 0.90f)
        )
        PremiumGlassRole.CONTROL -> Triple(
            14.dp,
            scheme.surfaceVariant.copy(alpha = if (dark) 0.48f else 0.52f),
            scheme.surfaceVariant.copy(alpha = if (dark) 0.90f else 0.88f)
        )
        PremiumGlassRole.ACCENT_CONTROL -> Triple(
            16.dp,
            scheme.primaryContainer.copy(alpha = if (dark) 0.58f else 0.64f),
            scheme.primaryContainer.copy(alpha = 0.92f)
        )
        PremiumGlassRole.STATUS -> Triple(
            18.dp,
            scheme.tertiaryContainer.copy(alpha = if (dark) 0.52f else 0.60f),
            scheme.tertiaryContainer.copy(alpha = 0.92f)
        )
        // Modal material is intentionally much denser than normal tiles. The high blur radius
        // and ~68% tint keep copy readable while still preserving a frosted sense of depth.
        PremiumGlassRole.MODAL -> Triple(
            40.dp,
            scheme.surface.copy(alpha = if (dark) 0.68f else 0.72f),
            scheme.surface.copy(alpha = if (dark) 0.96f else 0.94f)
        )
    }
    val blurRadius = LocalPremiumBlurRadiusOverride.current ?: baseBlurRadius

    return remember(scheme, role, blurRadius) {
        HazeStyle(
            backgroundColor = Color.Transparent,
            tints = listOf(HazeTint(tint)),
            blurRadius = blurRadius,
            // A tiny amount of material grain prevents large translucent surfaces from
            // looking like flat alpha rectangles without becoming visibly noisy.
            noiseFactor = 0.015f,
            fallbackTint = HazeTint(fallback)
        )
    }
}

/**
 * Real background blur for a compact tile/control.
 *
 * Haze reads only the single static page artwork registered by [PremiumBackdrop].
 * Live telemetry and menu content are intentionally not registered as blur sources, so updates do
 * not force the blur pipeline to recapture the whole UI. On Android 11 and below we use the
 * fallback tint instead of forcing the experimental RenderScript path.
 */
@Composable
fun Modifier.premiumGlass(
    shape: Shape = RoundedCornerShape(22.dp),
    role: PremiumGlassRole = PremiumGlassRole.TILE
): Modifier {
    val hazeState = LocalPremiumHazeState.current
    val style = glassStyle(role)

    if (hazeState == null) {
        val fallback = when (role) {
            PremiumGlassRole.TILE -> MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
            PremiumGlassRole.CONTROL -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.90f)
            PremiumGlassRole.ACCENT_CONTROL -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
            PremiumGlassRole.STATUS -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.92f)
            PremiumGlassRole.MODAL -> MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
        }
        return clip(shape).background(fallback)
    }

    val blurInputScale = when (role) {
        // Small controls are the most numerous effects on the dashboard. Haze's Android
        // benchmarks show that 0.5 input scaling can reduce blur cost while staying visually
        // close to full-resolution sampling for soft, compact controls.
        PremiumGlassRole.CONTROL,
        PremiumGlassRole.ACCENT_CONTROL -> 0.50f
        PremiumGlassRole.STATUS -> 0.55f
        PremiumGlassRole.TILE,
        PremiumGlassRole.MODAL -> 0.66f
    }

    return clip(shape).hazeEffect(
        state = hazeState,
        style = style
    ) {
        inputScale = HazeInputScale.Fixed(blurInputScale)
        blurEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    }
}

/**
 * Press feedback stays entirely in the draw layer.
 *
 * Reading the animated value inside graphicsLayer avoids recomposing the control on every
 * animation frame. Press-in is firm; release is slightly under-damped so controls rebound with
 * a subtle tactile bounce. Alpha is intentionally not animated because alpha < 1 forces an
 * extra offscreen compositing layer for the whole blurred control on Android.
 */
@Composable
fun Modifier.premiumPress(
    interactionSource: MutableInteractionSource,
    pressedScale: Float = 0.965f
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale = animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = if (pressed) {
            spring(
                dampingRatio = PremiumMotion.PressInDamping,
                stiffness = PremiumMotion.PressInStiffness
            )
        } else {
            spring(
                dampingRatio = PremiumMotion.PressReleaseDamping,
                stiffness = PremiumMotion.PressReleaseStiffness
            )
        },
        label = "premiumPressScale"
    )
    return graphicsLayer {
        val value = scale.value
        scaleX = value
        scaleY = value
    }
}

/** Glass card for content tiles. The Card itself stays transparent so the backdrop remains visible. */
@Composable
fun PremiumGlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    border: BorderStroke? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val glassModifier = modifier.premiumGlass(shape, PremiumGlassRole.TILE)
    if (onClick == null) {
        Card(
            modifier = glassModifier,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            border = border,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            content = content
        )
    } else {
        Card(
            onClick = onClick,
            modifier = glassModifier,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            border = border,
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            content = content
        )
    }
}

/** General glass tile, including selectable rows and status blocks. */
@Composable
fun PremiumGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    role: PremiumGlassRole = PremiumGlassRole.TILE,
    selectedTint: Color = Color.Transparent,
    border: BorderStroke? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val glassModifier = modifier
        .premiumGlass(shape, role)
        .then(if (selectedTint != Color.Transparent) Modifier.background(selectedTint, shape) else Modifier)

    if (onClick == null) {
        Surface(
            modifier = glassModifier,
            shape = shape,
            color = Color.Transparent,
            border = border,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            content = content
        )
    } else {
        Surface(
            onClick = onClick,
            modifier = glassModifier,
            shape = shape,
            color = Color.Transparent,
            border = border,
            shadowElevation = 0.dp,
            tonalElevation = 0.dp,
            content = content
        )
    }
}

@Composable
fun PremiumGlassButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.Transparent,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    content: @Composable RowScope.() -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    val interactionSource = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        modifier = modifier
            .premiumGlass(shape, PremiumGlassRole.ACCENT_CONTROL)
            .then(if (tint != Color.Transparent) Modifier.background(tint, shape) else Modifier)
            .premiumPress(interactionSource),
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = contentColor,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        ),
        elevation = null,
        interactionSource = interactionSource,
        content = content
    )
}

@Composable
fun PremiumGlassTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.Transparent,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable RowScope.() -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    val interactionSource = remember { MutableInteractionSource() }
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier
            .premiumGlass(shape, PremiumGlassRole.CONTROL)
            .then(if (tint != Color.Transparent) Modifier.background(tint, shape) else Modifier)
            .premiumPress(interactionSource),
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = Color.Transparent,
            contentColor = contentColor,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        ),
        interactionSource = interactionSource,
        content = content
    )
}

@Composable
fun PremiumGlassOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.Transparent,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable RowScope.() -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    val interactionSource = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .premiumGlass(shape, PremiumGlassRole.CONTROL)
            .then(if (tint != Color.Transparent) Modifier.background(tint, shape) else Modifier)
            // Exactly one outline at the glass boundary. Material's internal outlined-button
            // stroke is disabled below, which removes the smaller nested ring.
            .border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.34f)),
                shape
            )
            .premiumPress(interactionSource),
        enabled = enabled,
        shape = shape,
        border = null,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color.Transparent,
            contentColor = contentColor,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        ),
        interactionSource = interactionSource,
        content = content
    )
}

@Composable
fun PremiumGlassIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource
    ) {
        // Keep Material's full touch target, but make only the visible glass puck smaller.
        // Adjacent toolbar actions therefore have breathing room without sacrificing ergonomics.
        Box(
            modifier = Modifier
                .size(38.dp)
                .premiumGlass(CircleShape, PremiumGlassRole.CONTROL)
                .premiumPress(interactionSource, pressedScale = 0.94f),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

private fun Modifier.premiumBasePaint(scheme: androidx.compose.material3.ColorScheme): Modifier =
    drawWithCache {
        // Fully opaque page base. This is the part that belongs to each navigation destination.
        val lowerSurface = scheme.surfaceVariant
            .copy(alpha = 0.24f)
            .compositeOver(scheme.surface)
        val background = Brush.verticalGradient(
            listOf(
                scheme.background,
                scheme.surface,
                lowerSurface
            )
        )
        onDrawBehind {
            drawRect(background)
        }
    }

private fun Modifier.premiumGlowPaint(scheme: androidx.compose.material3.ColorScheme): Modifier =
    drawWithCache {
        val primaryCenter = Offset(size.width * 0.02f, size.height * 0.03f)
        val primaryRadius = size.minDimension * 0.42f
        val primaryGlow = Brush.radialGradient(
            colors = listOf(scheme.primary.copy(alpha = 0.12f), Color.Transparent),
            center = primaryCenter,
            radius = primaryRadius
        )
        val tertiaryCenter = Offset(size.width * 0.95f, size.height * 0.62f)
        val tertiaryRadius = size.minDimension * 0.32f
        val tertiaryGlow = Brush.radialGradient(
            colors = listOf(scheme.tertiary.copy(alpha = 0.09f), Color.Transparent),
            center = tertiaryCenter,
            radius = tertiaryRadius
        )
        onDrawBehind {
            drawCircle(primaryGlow, radius = primaryRadius, center = primaryCenter)
            drawCircle(tertiaryGlow, radius = tertiaryRadius, center = tertiaryCenter)
        }
    }

private fun Modifier.premiumBackdropPaint(scheme: androidx.compose.material3.ColorScheme): Modifier =
    premiumBasePaint(scheme).premiumGlowPaint(scheme)

/**
 * Fixed decorative light layer for navigation.
 *
 * The glows used to be painted inside every destination, so a pop animation physically moved
 * the light with the outgoing screen and then deleted it when that route left composition.
 * Keeping one glow layer outside route motion makes the lighting continuous across menu changes.
 */
@Composable
fun PremiumNavigationGlowOverlay(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .fillMaxSize()
            .premiumGlowPaint(scheme)
    )
}

/**
 * Opaque destination shell. It adds no blur source, scrim, or full-screen graphics layer.
 * Each route paints the same hard backdrop so route/setup transitions can never reveal the screen
 * underneath. Compact glass controls still sample the single cached source from [PremiumBackdrop].
 */
@Composable
fun PremiumPageSurface(content: @Composable BoxScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // Every destination owns an opaque copy of the static backdrop. During a route/setup slide,
    // one screen can never reveal or blend with the screen underneath it. Glass remains limited
    // to compact controls/tiles, which still sample the single root haze source.
    Box(
        Modifier
            .fillMaxSize()
            .background(scheme.background)
            // Route-owned surfaces are hard/opaque but contain no moving decorative lights.
            .premiumBasePaint(scheme)
    ) {
        CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
            content()
        }
    }
}

/**
 * The app's one and only blur source. It contains only static decorative artwork; menus, live
 * telemetry, lists, and navigation layers are drawn above it and are never blur sources.
 */
@Composable
fun PremiumBackdrop(content: @Composable BoxScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val hazeState = rememberHazeState()
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .premiumBackdropPaint(scheme)
                .hazeSource(hazeState)
        )
        CompositionLocalProvider(
            LocalContentColor provides scheme.onBackground,
            LocalPremiumHazeState provides hazeState
        ) {
            content()
        }
    }
}
