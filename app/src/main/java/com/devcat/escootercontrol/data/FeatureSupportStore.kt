package com.devcat.escootercontrol.data

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Local feature visibility for optional hardware. The stock app receives model-specific
 * capability data from its service; this independent client keeps user-confirmed support
 * on-device instead of depending on that service.
 */
@Immutable
data class FeatureFlags(
    val headlight: Boolean = true,
    val cruise: Boolean = true,
    val startupMode: Boolean = true,
    val ambientLight: Boolean = false,
    val seatLock: Boolean = false,
    val helmetLock: Boolean = false,
    val storageLock: Boolean = false,
    val brakeLock: Boolean = false
)

class FeatureSupportStore(context: Context) {
    private val prefs = context.getSharedPreferences("feature_support", Context.MODE_PRIVATE)

    private val _flags = MutableStateFlow(load())
    val flags: StateFlow<FeatureFlags> = _flags

    private fun load() = FeatureFlags(
        headlight = prefs.getBoolean("headlight", true),
        cruise = prefs.getBoolean("cruise", true),
        startupMode = prefs.getBoolean("startupMode", true),
        ambientLight = prefs.getBoolean("ambientLight", false),
        seatLock = prefs.getBoolean("seatLock", false),
        helmetLock = prefs.getBoolean("helmetLock", false),
        storageLock = prefs.getBoolean("storageLock", false),
        brakeLock = prefs.getBoolean("brakeLock", false)
    )

    fun update(transform: (FeatureFlags) -> FeatureFlags) {
        val updated = transform(_flags.value)
        _flags.value = updated
        prefs.edit()
            .putBoolean("headlight", updated.headlight)
            .putBoolean("cruise", updated.cruise)
            .putBoolean("startupMode", updated.startupMode)
            .putBoolean("ambientLight", updated.ambientLight)
            .putBoolean("seatLock", updated.seatLock)
            .putBoolean("helmetLock", updated.helmetLock)
            .putBoolean("storageLock", updated.storageLock)
            .putBoolean("brakeLock", updated.brakeLock)
            .apply()
    }
}
