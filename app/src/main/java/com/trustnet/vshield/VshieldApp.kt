package com.trustnet.vshield

import android.app.Application
import com.trustnet.vshield.core.DomainBlacklist

class VShieldApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DomainBlacklist.init(this)
    }
}
