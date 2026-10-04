package com.devcat.escootercontrol.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class SetupStep {
    WELCOME,
    BLUETOOTH,
    PAIR,
    NAME,
    APPEARANCE,
    READY
}

/**
 * Persists first-run onboarding locally so setup can resume after the app is closed.
 * No setup state leaves the device.
 */
class SetupStore(context: Context) {
    private val prefs = context.getSharedPreferences("first_run_setup", Context.MODE_PRIVATE)

    private val _completed = MutableStateFlow(prefs.getBoolean(KEY_COMPLETED, false))
    val completed: StateFlow<Boolean> = _completed

    private val _step = MutableStateFlow(loadStep())
    val step: StateFlow<SetupStep> = _step

    private val _demoDiscovery = MutableStateFlow(prefs.getBoolean(KEY_DEMO_DISCOVERY, false))
    val demoDiscovery: StateFlow<Boolean> = _demoDiscovery

    fun setStep(step: SetupStep) {
        _step.value = step
        prefs.edit().putString(KEY_STEP, step.name).apply()
    }

    /** Opens the pairing page in a fully local simulated-discovery mode. */
    fun startDemoDiscovery() {
        _demoDiscovery.value = true
        prefs.edit().putBoolean(KEY_DEMO_DISCOVERY, true).apply()
    }

    fun stopDemoDiscovery() {
        _demoDiscovery.value = false
        prefs.edit().putBoolean(KEY_DEMO_DISCOVERY, false).apply()
    }

    fun complete() {
        _completed.value = true
        _step.value = SetupStep.READY
        _demoDiscovery.value = false
        prefs.edit()
            .putBoolean(KEY_COMPLETED, true)
            .putString(KEY_STEP, SetupStep.READY.name)
            .putBoolean(KEY_DEMO_DISCOVERY, false)
            .apply()
    }

    fun restart() {
        _completed.value = false
        _step.value = SetupStep.WELCOME
        _demoDiscovery.value = false
        prefs.edit()
            .putBoolean(KEY_COMPLETED, false)
            .putString(KEY_STEP, SetupStep.WELCOME.name)
            .putBoolean(KEY_DEMO_DISCOVERY, false)
            .apply()
    }

    private fun loadStep(): SetupStep = runCatching {
        SetupStep.valueOf(prefs.getString(KEY_STEP, SetupStep.WELCOME.name) ?: SetupStep.WELCOME.name)
    }.getOrDefault(SetupStep.WELCOME)

    private companion object {
        const val KEY_COMPLETED = "completed"
        const val KEY_STEP = "step"
        const val KEY_DEMO_DISCOVERY = "demo_discovery"
    }
}
