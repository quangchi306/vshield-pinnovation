package com.trustnet.vshield.data.local

import android.content.Context

class SyncPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("vshield_sync", Context.MODE_PRIVATE)

    var blocklistVersion: Int
        get()      = prefs.getInt("blocklist_version", 0)
        set(value) = prefs.edit().putInt("blocklist_version", value).apply()

    // ← MỚI: lưu version whitelist để delta sync
    var whitelistVersion: Int
        get()      = prefs.getInt("whitelist_version", 0)
        set(value) = prefs.edit().putInt("whitelist_version", value).apply()

    var lastSyncTime: Long
        get()      = prefs.getLong("last_sync_time", 0L)
        set(value) = prefs.edit().putLong("last_sync_time", value).apply()

    var needsFullSync: Boolean
        get()      = prefs.getBoolean("needs_full_sync", true)
        set(value) = prefs.edit().putBoolean("needs_full_sync", value).apply()
}