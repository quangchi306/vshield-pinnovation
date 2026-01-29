package com.trustnet.vshield.core

import androidx.lifecycle.MutableLiveData

object VpnStats {
    val isRunning = MutableLiveData(false)
    val blockedCount = MutableLiveData(0L)
    val lastQueryDomain = MutableLiveData("-")
    val lastBlockedDomain = MutableLiveData("-")
}
