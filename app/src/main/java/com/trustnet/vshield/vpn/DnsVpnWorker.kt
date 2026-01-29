package com.trustnet.vshield.vpn

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
    private val blocked = AtomicLong(0)
    private val scope = CoroutineScope(Dispatchers.IO) // Xử lý I/O trên pool thread riêng
    private var job: Job? = null

    // Stream để đọc/ghi vào TUN interface
    private val inStream = FileInputStream(tun.fileDescriptor)
    private val outStream = FileOutputStream(tun.fileDescriptor)

    fun start() {
        job = scope.launch {
            val buffer = ByteArray(32767) // MTU buffer
            Log.i(TAG, "VPN Worker started listening...")

            while (isActive && service.isRunning()) {
                try {
                    // 1. Đọc gói tin từ TUN (Hành động này block thread đọc, nhưng ok vì ta đang trong coroutine IO)
                    val length = inStream.read(buffer)
                    if (length > 0) {
                        val packetData = buffer.copyOf(length)

                        // 2. Xử lý gói tin ở một coroutine con (Fire-and-forget)
                        // Giúp việc xử lý gói tin này không chặn gói tin tiếp theo
                        launch {
                            processPacket(packetData)
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "Error reading from TUN: ${e.message}")
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
        // Parse IP/UDP
        val udpPacket = UdpIpv4Packet.parse(packetData) ?: return

        // Chỉ xử lý traffic DNS (UDP port 53) gửi tới IP ảo của VPN
        if (udpPacket.dstPort != 53 || udpPacket.dstIp != VShieldVpnService.VPN_DNS_IP_INT) {
            return
        }

        val dnsPayload = udpPacket.payload
        val qDomain = DnsMessageParser.extractQueryDomain(dnsPayload) ?: return

        // Update UI (Log domain đang query)
        VpnStats.lastQueryDomain.postValue(qDomain)

        val responsePayload: ByteArray?

        if (DomainBlacklist.isBlocked(qDomain)) {
            // CASE 1: BỊ CHẶN -> Trả về NXDOMAIN ngay lập tức
            val count = blocked.incrementAndGet()
            VpnStats.blockedCount.postValue(count)
            VpnStats.lastBlockedDomain.postValue(qDomain)
            responsePayload = DnsMessageBuilder.buildNxdomainResponse(dnsPayload)
        } else {
            // CASE 2: CHO PHÉP -> Forward lên Cloudflare (1.1.1.1)
            responsePayload = forwardToUpstream(dnsPayload)
        }

        if (responsePayload != null && responsePayload.isNotEmpty()) {
            // Đóng gói lại thành IP packet
            val ipResponse = PacketBuilder.buildUdpIpv4Packet(
                srcIp = udpPacket.dstIp,   // Đảo ngược IP: Src là DNS Server (VPN)
                dstIp = udpPacket.srcIp,   // Dst là Client (App/Browser)
                srcPort = udpPacket.dstPort,
                dstPort = udpPacket.srcPort,
                payload = responsePayload
            )

            // Ghi trả lại vào TUN interface
            // Cần synchronize vì nhiều coroutine có thể ghi cùng lúc
            synchronized(outStream) {
                try {
                    outStream.write(ipResponse)
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing to TUN: ${e.message}")
                }
            }
        }
    }

    /**
     * Gửi query lên DNS thật (Upstream) và chờ phản hồi.
     * Sử dụng socket riêng cho mỗi request để tránh race condition.
     */
    private fun forwardToUpstream(dnsQuery: ByteArray): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()

            // CỰC KỲ QUAN TRỌNG: Protect socket để traffic này đi thẳng ra Wifi/4G,
            // không quay ngược lại vào VPN (tránh vòng lặp).
            if (!service.protect(socket)) {
                Log.e(TAG, "Cannot protect socket, dropping packet.")
                return null
            }

            socket.soTimeout = 2500 // Timeout ngắn để fail nhanh nếu mạng lag

            val upstreamAddr = InetSocketAddress(VShieldVpnService.UPSTREAM_DNS_IP, 53)
            val packet = DatagramPacket(dnsQuery, dnsQuery.size, upstreamAddr)
            socket.send(packet)

            val buf = ByteArray(4096)
            val response = DatagramPacket(buf, buf.size)
            socket.receive(response)

            buf.copyOf(response.length)
        } catch (e: Exception) {
            Log.w(TAG, "Upstream DNS query failed: ${e.message}")
            null // Trả về null để Client tự retry hoặc timeout
        } finally {
            socket?.close()
        }
    }

    companion object {
        private const val TAG = "DnsVpnWorker"
    }
}