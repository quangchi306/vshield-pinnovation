package com.trustnet.vshield

import android.app.Application
import android.util.Log
import com.trustnet.vshield.core.DomainBlacklist
import com.trustnet.vshield.sync.BlocklistSyncWorker

class VShieldApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Xóa bin cache cũ nếu version code thay đổi (tránh lỗi tương thích)
        val prefs = getSharedPreferences("vshield_prefs", MODE_PRIVATE)
        val lastVersion = prefs.getInt("app_version", 0)
        val currentVersion = BuildConfig.VERSION_CODE
        if (lastVersion != currentVersion) {
            Log.i("VShieldApp", "Phiên bản thay đổi ($lastVersion -> $currentVersion), xóa bin cache cũ...")
            DomainBlacklist.clearBinCache(this)
            prefs.edit().putInt("app_version", currentVersion).apply()
        }

        // Đăng ký sync tự động (mỗi 12h)
        BlocklistSyncWorker.schedule(this)
    }
}