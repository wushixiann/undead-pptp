// SPDX-License-Identifier: GPL-3.0-or-later
package com.pptp.client.util

import android.content.Context
import android.content.SharedPreferences

/**
 * Tiny wrapper around SharedPreferences for remembering the last connection.
 * v0.1.0 stores only non-sensitive values (server/port/username). The password
 * is intentionally NOT persisted — pasting it each connection is a small UX
 * cost for a meaningful security benefit, even given PPTP's already-weak
 * threat model.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME, Context.MODE_PRIVATE,
    )

    var server: String
        get() = prefs.getString(KEY_SERVER, "") ?: ""
        set(v) { prefs.edit().putString(KEY_SERVER, v).apply() }

    var port: Int
        get() = prefs.getInt(KEY_PORT, 1723)
        set(v) { prefs.edit().putInt(KEY_PORT, v).apply() }

    var username: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""
        set(v) { prefs.edit().putString(KEY_USERNAME, v).apply() }

    fun saveConnection(server: String, port: Int, username: String) {
        prefs.edit()
            .putString(KEY_SERVER, server)
            .putInt(KEY_PORT, port)
            .putString(KEY_USERNAME, username)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "pptp_client"
        private const val KEY_SERVER = "server"
        private const val KEY_PORT = "port"
        private const val KEY_USERNAME = "username"
    }
}
