package com.trustnet.vshield

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.trustnet.vshield.core.DomainBlacklist
import com.trustnet.vshield.core.VpnStats
import com.trustnet.vshield.ui.screen.BlockedActivity // Import màn hình mới
import com.trustnet.vshield.vpn.DnsVpnWorker
import com.trustnet.vshield.vpn.packet.UdpIpv4Packet

class VShieldVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var worker: DnsVpnWorker? = null
    @Volatile private var running: Boolean = false

    override fun onCreate() {
        super.onCreate()
        DomainBlacklist.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Xử lý lệnh Bypass từ màn hình đỏ
        if (intent?.action == ACTION_ALLOW_DOMAIN) {
            val domain = intent.getStringExtra(EXTRA_DOMAIN)
            if (domain != null) {
                DomainBlacklist.addTemporaryAllow(domain)
            }
        }

        if (intent?.action == ACTION_UPDATE_SETTINGS) {
            val prefs = getSharedPreferences("VShieldPrefs", MODE_PRIVATE)
            DomainBlacklist.blockAdult = prefs.getBoolean("BLOCK_ADULT", true)
            DomainBlacklist.blockGambling = prefs.getBoolean("BLOCK_GAMBLING", true)
        }

        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    fun isRunning(): Boolean = running

    private fun startVpn() {
        if (running) return
        running = true
        VpnStats.isRunning.postValue(true)
        startInForeground()

        val builder = Builder()
            .setSession("V-Shield Home")
            .setMtu(MTU)
            .addAddress(VPN_CLIENT_IP, 32)
            .addRoute(VPN_DNS_IP, 32)
            .addDnsServer(VPN_DNS_IP)
            .setBlocking(true)

        try {
            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                running = false
                stopSelf()
                return
            }
            worker = DnsVpnWorker(this, vpnInterface!!)
            worker?.start()
        } catch (e: Exception) {
            e.printStackTrace()
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
    }

    private fun startInForeground() {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(NOTIF_CH, "V-Shield VPN", NotificationManager.IMPORTANCE_LOW)
            mgr.createNotificationChannel(ch)
        }

        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIF_CH)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("V-Shield đang bảo vệ")
            .setContentText("Hệ thống lọc đang chạy ngầm")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(this, NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // --- HÀM MỚI: MỞ MÀN HÌNH CHẶN ---
    fun openBlockingScreen(domain: String) {
        // Kiểm tra quyền "Display over other apps"
        if (Settings.canDrawOverlays(this)) {
            val intent = Intent(this, BlockedActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // Bắt buộc khi gọi từ Service
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.putExtra("BLOCKED_DOMAIN", domain)
            startActivity(intent)
        } else {
            // Nếu chưa cấp quyền, đành hiện thông báo như cũ
            showBlockingNotification(domain)
        }
    }

    // Giữ lại hàm thông báo để fallback
    fun showBlockingNotification(domain: String) {
        // ... (Giữ nguyên code hàm này từ bài trước nếu muốn, hoặc bỏ qua)
    }

    companion object {
        const val ACTION_START = "vn.ptit.vshieldhome.action.START"
        const val ACTION_STOP = "vn.ptit.vshieldhome.action.STOP"
        const val ACTION_UPDATE_SETTINGS = "vn.ptit.vshieldhome.action.UPDATE_SETTINGS"
        const val ACTION_ALLOW_DOMAIN = "vn.ptit.vshieldhome.action.ALLOW"
        const val EXTRA_DOMAIN = "extra_domain"

        const val MTU = 1500
        const val VPN_DNS_IP = "10.10.0.1"
        const val VPN_CLIENT_IP = "10.10.0.2"
        const val UPSTREAM_DNS_IP = "1.1.1.1"

        val VPN_DNS_IP_INT: Int = UdpIpv4Packet.ipStringToInt(VPN_DNS_IP)

        private const val NOTIF_CH = "vshield_vpn"
        private const val NOTIF_ID = 1001
    }
}