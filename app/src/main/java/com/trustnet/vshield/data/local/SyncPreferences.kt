package com.trustnet.vshield.data.local

import android.content.Context
import androidx.core.content.edit

class SyncPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("vshield_sync", Context.MODE_PRIVATE)

    var blocklistVersion: Int
        get()      = prefs.getInt(KEY_VERSION, 0)
        set(value) = prefs.edit { putInt(KEY_VERSION, value) }

    var lastSyncTime: Long
        get()      = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit { putLong(KEY_LAST_SYNC, value) }

    var needsFullSync: Boolean
        get()      = prefs.getBoolean(KEY_NEEDS_FULL, true)
        set(value) = prefs.edit { putBoolean(KEY_NEEDS_FULL, value) }

    fun reset() {
        blocklistVersion = 0
        lastSyncTime     = 0L
        needsFullSync    = true
    }

    companion object {
        private const val KEY_VERSION    = "blocklist_version"
        private const val KEY_LAST_SYNC  = "last_sync_time"
        private const val KEY_NEEDS_FULL = "needs_full_sync"
    }
}
