package com.devcat.escootercontrol.data

import android.content.Context
import com.devcat.escootercontrol.ui.theme.AppThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AppearanceStore(context: Context) {
    private val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val _themeMode = MutableStateFlow(loadThemeMode())
    val themeMode: StateFlow<AppThemeMode> = _themeMode

    fun setThemeMode(mode: AppThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    private fun loadThemeMode(): AppThemeMode = runCatching {
        AppThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: AppThemeMode.SYSTEM.name)
    }.getOrDefault(AppThemeMode.SYSTEM)

    private companion object {
        const val KEY_THEME = "theme_mode"
    }
}
