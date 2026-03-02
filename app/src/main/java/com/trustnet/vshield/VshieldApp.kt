package com.trustnet.vshield

import android.app.Application
import com.trustnet.vshield.sync.BlocklistSyncWorker

class VShieldApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Đăng ký sync tự động mỗi 12h
        BlocklistSyncWorker.schedule(this)
    }
}
