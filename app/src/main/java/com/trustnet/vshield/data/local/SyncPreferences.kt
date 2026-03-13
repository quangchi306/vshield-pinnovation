package com.trustnet.vshield.data.local

import android.content.Context

class SyncPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("vshield_sync", Context.MODE_PRIVATE)

    var blocklistVersion: Int
        get()      = prefs.getInt("blocklist_version", 0)
        set(value) = prefs.edit().putInt("blocklist_version", value).apply()

    var whitelistVersion: Int
        get()      = prefs.getInt("whitelist_version", 0)
        set(value) = prefs.edit().putInt("whitelist_version", value).apply()

    var lastSyncTime: Long
        get()      = prefs.getLong("last_sync_time", 0L)
        set(value) = prefs.edit().putLong("last_sync_time", value).apply()

    // Lưu riêng thời điểm sync whitelist lần cuối
    // Dùng để quyết định có cần full sync whitelist không (mỗi 7 ngày)
    var lastWhitelistSyncTime: Long
        get()      = prefs.getLong("last_whitelist_sync_time", 0L)
        set(value) = prefs.edit().putLong("last_whitelist_sync_time", value).apply()

    var needsFullSync: Boolean
        get()      = prefs.getBoolean("needs_full_sync", true)
        set(value) = prefs.edit().putBoolean("needs_full_sync", value).apply()

    // Whitelist cần full sync nếu chưa sync lần nào hoặc đã hơn 7 ngày
    val needsWhitelistFullSync: Boolean
        get() {
            if (whitelistVersion == 0) return true
            val sevenDaysMs = 7 * 24 * 60 * 60 * 1000L
            return System.currentTimeMillis() - lastWhitelistSyncTime > sevenDaysMs
        }
}