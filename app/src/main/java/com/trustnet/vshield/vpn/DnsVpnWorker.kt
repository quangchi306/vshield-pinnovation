package com.trustnet.vshield.vpn

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.trustnet.vshield.VShieldVpnService
import com.trustnet.vshield.core.DomainBlacklist
import com.trustnet.vshield.core.VpnStats
import com.trustnet.vshield.vpn.dns.DnsMessageBuilder
import com.trustnet.vshield.vpn.dns.DnsMessageParser
import com.trustnet.vshield.vpn.packet.PacketBuilder
import com.trustnet.vshield.vpn.packet.UdpIpv4Packet
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

class DnsVpnWorker(
    private val service: VShieldVpnService,
    private val tun: ParcelFileDescriptor
) {
    // Biến đếm số lượng chặn
    private val blocked = AtomicLong(0)

    // Scope chạy trên IO thread để xử lý mạng
    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    // Handler để giao tiếp với Main Thread (để gọi lệnh mở Activity/Notification)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Stream đọc/ghi dữ liệu vào VPN interface
    private val inStream = FileInputStream(tun.fileDescriptor)
    private val outStream = FileOutputStream(tun.fileDescriptor)

    fun start() {
        job = scope.launch {
            val buffer = ByteArray(32767) // Buffer đủ lớn cho gói tin IP
            while (isActive && service.isRunning()) {
                try {
                    // Đọc gói tin từ hệ điều hành gửi xuống
                    val length = inStream.read(buffer)
                    if (length > 0) {
                        val packetData = buffer.copyOf(length)
                        // Xử lý song song từng gói tin
                        launch { processPacket(packetData) }
                    }
                } catch (e: Exception) {
                    if (isActive) Log.e("DnsWorker", "Error reading packet: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        try { inStream.close() } catch (_: Exception) {}
        try { outStream.close() } catch (_: Exception) {}
    }

    private fun processPacket(packetData: ByteArray) {
        // Parse header IP/UDP
        val udpPacket = UdpIpv4Packet.parse(packetData) ?: return

        // Chỉ xử lý traffic DNS (Port 53) gửi đến IP ảo của VPN
        if (udpPacket.dstPort != 53 || udpPacket.dstIp != VShieldVpnService.VPN_DNS_IP_INT) {
            return
        }

        // Lấy nội dung DNS query
        val dnsPayload = udpPacket.payload
        val qDomain = DnsMessageParser.extractQueryDomain(dnsPayload) ?: return

        // Cập nhật thống kê domain đang truy cập
        VpnStats.lastQueryDomain.postValue(qDomain)

        val responsePayload: ByteArray?

        // --- LOGIC CHẶN ---
        if (DomainBlacklist.isBlocked(qDomain)) {
            // 1. Tăng bộ đếm thống kê
            val count = blocked.incrementAndGet()
            VpnStats.blockedCount.postValue(count)
            VpnStats.lastBlockedDomain.postValue(qDomain)

            // 2. Gửi lệnh mở màn hình cảnh báo (Chạy trên Main Thread)
            // Đây là điểm thay đổi quan trọng để hiện màn hình đỏ thay vì chỉ thông báo
            mainHandler.post {
                service.openBlockingScreen(qDomain)
            }

            // 3. Trả về NXDOMAIN (Tên miền không tồn tại) để trình duyệt dừng tải ngay lập tức
            responsePayload = DnsMessageBuilder.buildNxdomainResponse(dnsPayload)
            Log.d("VShield", "BLOCKED: $qDomain")
        } else {
            // Cho phép: Gửi query đi ra Internet (Cloudflare/Google DNS)
            responsePayload = forwardToUpstream(dnsPayload)
        }

        // Ghi phản hồi vào Interface để trả về cho ứng dụng
        if (responsePayload != null && responsePayload.isNotEmpty()) {
            val ipResponse = PacketBuilder.buildUdpIpv4Packet(
                srcIp = udpPacket.dstIp,   // Đảo ngược nguồn/đích
                dstIp = udpPacket.srcIp,
                srcPort = udpPacket.dstPort,
                dstPort = udpPacket.srcPort,
                payload = responsePayload
            )

            // Đồng bộ hóa việc ghi để tránh lỗi thread
            synchronized(outStream) {
                try { outStream.write(ipResponse) } catch (_: Exception) {}
            }
        }
    }

    // Hàm gửi DNS query ra mạng thật
    private fun forwardToUpstream(dnsQuery: ByteArray): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            // QUAN TRỌNG: Bảo vệ socket để gói tin này đi thẳng ra Wifi/4G,
            // không bị quay ngược lại vào VPN (tránh vòng lặp vô tận)
            if (!service.protect(socket)) return null

            socket.soTimeout = 2500 // Timeout 2.5 giây

            val upstreamAddr = InetSocketAddress(VShieldVpnService.UPSTREAM_DNS_IP, 53)
            val packet = DatagramPacket(dnsQuery, dnsQuery.size, upstreamAddr)
            socket.send(packet)

            val buf = ByteArray(4096)
            val response = DatagramPacket(buf, buf.size)
            socket.receive(response)
            buf.copyOf(response.length)
        } catch (e: Exception) {
            // Nếu lỗi mạng hoặc timeout, trả về null
            null
        } finally {
            socket?.close()
        }
    }
}