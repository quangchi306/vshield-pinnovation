package com.trustnet.vshield

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.trustnet.vshield.core.VpnStats
import com.trustnet.vshield.vpn.DnsVpnWorker
import com.trustnet.vshield.vpn.packet.UdpIpv4Packet

class VShieldVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var worker: DnsVpnWorker? = null
    @Volatile private var running: Boolean = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> stopVpn()
        }
        return START_STICKY
    }

    override fun onRevoke() {
        // Hệ thống gọi khi người dùng disconnect/forget trong Settings VPN.
        stopVpn()
        super.onRevoke()
    }

    fun isRunning(): Boolean = running

    private fun startVpn() {
        if (running) return
        running = true
        VpnStats.isRunning.postValue(true)
        startInForeground()

        // Setup VPN interface như cũ
        val builder = Builder()
            .setSession("V-Shield Home")
            .setMtu(MTU)
            .addAddress(VPN_CLIENT_IP, 32)
            .addRoute(VPN_DNS_IP, 32)
            .addDnsServer(VPN_DNS_IP)
            .setBlocking(true)

        vpnInterface = builder.establish()
        if (vpnInterface == null) {
            running = false
            stopSelf()
            return
        }

        // KHỞI TẠO VÀ CHẠY WORKER MỚI
        worker = DnsVpnWorker(this, vpnInterface!!)
        worker?.start()
    }

    private fun stopVpn() {
        if (!running) return
        running = false
        VpnStats.isRunning.postValue(false)

        // DỪNG WORKER
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
            val ch = NotificationChannel(
                NOTIF_CH,
                "V-Shield VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            mgr.createNotificationChannel(ch)
        }

        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NOTIF_CH)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("V-Shield Home đang bật")
            .setContentText("Đang lọc DNS & chặn website lừa đảo (demo)")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        // Android 14+ cần foreground service type; specialUse theo docs
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        const val ACTION_START = "vn.ptit.vshieldhome.action.START"
        const val ACTION_STOP = "vn.ptit.vshieldhome.action.STOP"

        const val MTU = 1500
        const val VPN_DNS_IP = "10.10.0.1"
        const val VPN_CLIENT_IP = "10.10.0.2"
        const val UPSTREAM_DNS_IP = "1.1.1.1"

        val VPN_DNS_IP_INT: Int = UdpIpv4Packet.ipStringToInt(VPN_DNS_IP)

        private const val NOTIF_CH = "vshield_vpn"
        private const val NOTIF_ID = 1001
    }
}
