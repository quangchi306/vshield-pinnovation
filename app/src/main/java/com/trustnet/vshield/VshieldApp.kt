package com.trustnet.vshield

import android.app.Application
import com.trustnet.vshield.sync.BlocklistSyncWorker

class VShieldApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // ĐÃ XÓA KHỞI TẠO AI Ở ĐÂY ĐỂ NHƯỜNG CHO MÀN HÌNH LOADING

        // Vẫn giữ đăng ký sync tự động chạy ngầm mỗi 12h
        BlocklistSyncWorker.schedule(this)
    }
}