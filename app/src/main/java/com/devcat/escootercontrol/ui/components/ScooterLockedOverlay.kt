package com.devcat.escootercontrol.ui.components

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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/**
 * App-wide lock state shown whenever the scooter reports itself locked.
 *
 * The scrim is a real input target, so routes below it cannot be tapped or scrolled.
 * MainActivity applies the only allowed full-screen blur to the content underneath this modal.
 * The scrim stays intentionally light so the blurred dashboard remains recognizable.
 */
@Composable
fun ScooterLockedOverlay(
    visible: Boolean,
    onUnlock: () -> Unit
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
            val blocker = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.24f))
                    .clickable(
                        interactionSource = blocker,
                        indication = null,
                        onClick = {}
                    )
            )
        }

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
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                PremiumGlassSurface(
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .widthIn(max = 420.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(34.dp),
                    role = PremiumGlassRole.MODAL,
                    border = BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.48f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(
                            modifier = Modifier.size(132.dp),
                            shape = CircleShape,
                            // Keep the lock puck fully opaque. The previous 92% alpha let the
                            // modal's blurred/noisy material faintly show through in Light theme,
                            // which could read as a polygon/hex shape inside the circle.
                            color = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                            border = BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.10f)
                            ),
                            shadowElevation = 8.dp
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.Lock,
                                    contentDescription = null,
                                    modifier = Modifier.size(70.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(24.dp))
                        Text(
                            text = "Scooter locked",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Controls are disabled until you unlock it.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(24.dp))

                        PremiumGlassButton(
                            onClick = onUnlock,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(58.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ) {
                            Icon(Icons.Filled.LockOpen, contentDescription = null)
                            Spacer(Modifier.width(10.dp))
                            Text("Unlock scooter", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
