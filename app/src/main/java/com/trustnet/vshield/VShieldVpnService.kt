package com.trustnet.vshield

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.trustnet.vshield.core.DomainBlacklist
import com.trustnet.vshield.core.VpnStats
import com.trustnet.vshield.network.OnDeviceAi
import com.trustnet.vshield.parenting.ParentingPrefs
import com.trustnet.vshield.vpn.DnsVpnWorker
import com.trustnet.vshield.vpn.packet.UdpIpv4Packet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VShieldVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var worker: DnsVpnWorker? = null
    @Volatile private var running: Boolean = false

    @Volatile private var isParentingMode: Boolean = false

    override fun onCreate() {
        super.onCreate()
        Log.i("VpnService", "onCreate")

        if (!DomainBlacklist.isListsReady()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val loaded = if (DomainBlacklist.hasBinCache(applicationContext)) {
                        DomainBlacklist.loadFromBinCache(applicationContext)
                    } else {
                        false
                    }
                    if (!loaded) {
                        DomainBlacklist.reloadFromDatabaseSync(applicationContext)
                    }
                    Log.i("VpnService", "DomainBlacklist loaded after restart")
                } catch (e: Exception) {
                    Log.e("VpnService", "Error loading DomainBlacklist: ${e.message}")
                }
            }

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    OnDeviceAi.init(applicationContext)
                    Log.i("VpnService", "OnDeviceAi loaded after restart")
                } catch (e: Exception) {
                    Log.e("VpnService", "Error loading OnDeviceAi: ${e.message}")
                }
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            ParentingPrefs(this@VShieldVpnService).data.collect { prefsData ->
                isParentingMode = prefsData.parentingEnabled
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ALLOW_DOMAIN -> {
                if (isParentingMode) {
                    Toast.makeText(this, "Không thể bỏ qua chặn trong Chế độ Phụ huynh!", Toast.LENGTH_SHORT).show()
                    getSystemService(NotificationManager::class.java).cancel(BLOCK_NOTIF_ID)
                } else {
                    intent.getStringExtra(EXTRA_DOMAIN)?.let { domain ->
                        DomainBlacklist.addTemporaryAllow(domain)
                        uiMuteUntil = System.currentTimeMillis() + 15000
                        getSystemService(NotificationManager::class.java).cancel(BLOCK_NOTIF_ID)
                        Toast.makeText(this, "Đã cho phép: $domain. Vui lòng tải lại trang!", Toast.LENGTH_LONG).show()
                    }
                }
                return START_STICKY
            }
            ACTION_UPDATE_SETTINGS -> {
                val prefs = getSharedPreferences("VShieldPrefs", MODE_PRIVATE)
                DomainBlacklist.blockAdult = prefs.getBoolean("BLOCK_ADULT", true)
                DomainBlacklist.blockGambling = prefs.getBoolean("BLOCK_GAMBLING", true)
                return START_STICKY
            }
        }

        when (intent?.action) {
            ACTION_START -> {
                Log.i("VpnService", "ACTION_START received")
                startInForeground()
                CoroutineScope(Dispatchers.IO).launch {
                    startVpn()
                }
            }
            ACTION_STOP -> {
                Log.i("VpnService", "ACTION_STOP received")
                stopVpn()
            }
            null -> {
                // Service restarted by system (START_STICKY)
                Log.i("VpnService", "Restart with null intent")
                startInForeground()
                CoroutineScope(Dispatchers.IO).launch {
                    startVpn()
                }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    fun isRunning(): Boolean = running

    private suspend fun startVpn() {
        if (running) {
            Log.i("VpnService", "VPN already running")
            return
        }

        // Wait for filter to be ready (max 10 seconds)
        val ready = DomainBlacklist.awaitLoad(10000)
        if (!ready) {
            Log.e("VpnService", "Filter not ready after 10s, cannot start VPN")
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@VShieldVpnService,
                    "Không thể khởi động VShield: bộ lọc chưa sẵn sàng.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        running = true
        VpnStats.isRunning.postValue(true)
        Log.i("VpnService", "VPN starting...")

        val builder = Builder()
            .setSession("VShield Home")
            .setMtu(MTU)
            .addAddress(VPN_CLIENT_IP, 32)
            .addRoute(VPN_DNS_IP, 32)
            .addDnsServer(VPN_DNS_IP)
            .setBlocking(true)

        try {
            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e("VpnService", "establish() returned null")
                running = false
                VpnStats.isRunning.postValue(false)
                stopSelf()
                return
            }
            worker = DnsVpnWorker(this@VShieldVpnService, vpnInterface!!)
            worker?.start()
            Log.i("VpnService", "VPN started successfully")
        } catch (e: Exception) {
            Log.e("VpnService", "Error starting VPN: ${e.message}", e)
            stopVpn()
        }
    }

    private fun stopVpn() {
        if (!running) return
        running = false
        VpnStats.isRunning.postValue(false)
        worker?.stop()
        worker = null
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i("VpnService", "VPN stopped")
    }

    private fun startInForeground() {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(NOTIF_CH, "VShield VPN", NotificationManager.IMPORTANCE_LOW)
            mgr.createNotificationChannel(ch)

            val alertCh = NotificationChannel(ALERT_NOTIF_CH, "Cảnh báo chặn", NotificationManager.IMPORTANCE_HIGH)
            alertCh.description = "Hiển thị cảnh báo khi chặn các trang web độc hại"
            mgr.createNotificationChannel(alertCh)
        }

        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIF_CH)
            .setSmallIcon(android.R.drawable.ic_secure)
            .setContentTitle("VShield đang bảo vệ")
            .setContentText("Hệ thống lọc đang chạy ngầm")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(this, NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
        Log.i("VpnService", "Foreground service started")
    }

    fun showBlockingNotification(domain: String, reason: String = "Nội dung không phù hợp") {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val notificationBuilder = NotificationCompat.Builder(this, ALERT_NOTIF_CH)
            .setSmallIcon(android.R.drawable.ic_secure)
            .setContentTitle("Truy cập bị hạn chế")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)

        if (isParentingMode) {
            notificationBuilder.setContentText("Đã chặn: $domain ($reason)")
        } else {
            val bypassIntent = Intent(this, VShieldVpnService::class.java).apply {
                action = ACTION_ALLOW_DOMAIN
                putExtra(EXTRA_DOMAIN, domain)
            }
            val bypassPendingIntent = PendingIntent.getService(
                this,
                domain.hashCode(),
                bypassIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            notificationBuilder.setContentText("Đã chặn: $domain ($reason)")
            notificationBuilder.addAction(
                android.R.drawable.ic_menu_revert,
                "Vẫn tiếp tục truy cập",
                bypassPendingIntent
            )
        }

        mgr.notify(BLOCK_NOTIF_ID, notificationBuilder.build())
    }

    companion object {
        const val ACTION_START           = "vn.ptit.vshieldhome.action.START"
        const val ACTION_STOP            = "vn.ptit.vshieldhome.action.STOP"
        const val ACTION_UPDATE_SETTINGS = "vn.ptit.vshieldhome.action.UPDATE_SETTINGS"
        const val ACTION_ALLOW_DOMAIN    = "vn.ptit.vshieldhome.action.ALLOW"
        const val EXTRA_DOMAIN           = "extra_domain"

        const val MTU            = 1500
        const val VPN_DNS_IP     = "10.10.0.1"
        const val VPN_CLIENT_IP  = "10.10.0.2"
        const val UPSTREAM_DNS_IP = "1.1.1.1"

        val VPN_DNS_IP_INT: Int = UdpIpv4Packet.ipStringToInt(VPN_DNS_IP)

        private const val NOTIF_CH       = "vshield_vpn"
        private const val NOTIF_ID       = 1001
        private const val ALERT_NOTIF_CH = "vshield_alerts"
        private const val BLOCK_NOTIF_ID = 1002

        @Volatile var uiMuteUntil: Long = 0L
    }
}