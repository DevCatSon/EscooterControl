package com.devcat.escootercontrol.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The scooter this phone last connected to, kept so the app can reconnect without scanning. */
data class SavedScooter(val address: String, val name: String)

/**
 * Local-only storage (SharedPreferences, nothing leaves the phone):
 *  - the last scooter that reached the READY state, for one-tap / automatic reconnect
 *  - an optional nickname per scooter address. BLE gives us no way to rename the scooter itself,
 *    so a nickname only changes how this app labels it.
 */
class ScooterStore(context: Context) {
    private val prefs = context.getSharedPreferences("scooter_store", Context.MODE_PRIVATE)

    private val _last = MutableStateFlow(loadLast())
    val last: StateFlow<SavedScooter?> = _last

    private val _nicknames = MutableStateFlow(loadNicknames())
    val nicknames: StateFlow<Map<String, String>> = _nicknames

    private fun loadLast(): SavedScooter? {
        val address = prefs.getString(KEY_LAST_ADDRESS, null) ?: return null
        return SavedScooter(address, prefs.getString(KEY_LAST_NAME, null) ?: "Scooter")
    }

    private fun loadNicknames(): Map<String, String> =
        prefs.all.entries
            .filter { it.key.startsWith(NICK_PREFIX) && it.value is String }
            .associate { it.key.removePrefix(NICK_PREFIX) to (it.value as String) }

    fun saveLast(address: String, name: String) {
        prefs.edit().putString(KEY_LAST_ADDRESS, address).putString(KEY_LAST_NAME, name).apply()
        _last.value = SavedScooter(address, name)
    }

    /** Stops automatic reconnect. Nicknames are kept. */
    fun forgetLast() {
        prefs.edit().remove(KEY_LAST_ADDRESS).remove(KEY_LAST_NAME).apply()
        _last.value = null
    }

    /** Blank clears the nickname. Names are trimmed and capped so they fit a title bar. */
    fun setNickname(address: String, nickname: String) {
        val clean = nickname.trim().take(MAX_NICKNAME)
        prefs.edit().apply {
            if (clean.isEmpty()) remove(NICK_PREFIX + address) else putString(NICK_PREFIX + address, clean)
        }.apply()
        _nicknames.value = loadNicknames()
    }

    companion object {
        const val MAX_NICKNAME = 32
        private const val KEY_LAST_ADDRESS = "last_address"
        private const val KEY_LAST_NAME = "last_name"
        private const val NICK_PREFIX = "nick:"

        /** What to show for a scooter: its nickname, else the advertised name, else a generic label. */
        fun displayName(address: String?, advertised: String?, nicknames: Map<String, String>): String =
            (address?.let { nicknames[it] }) ?: advertised ?: "My Scooter"
    }
}
