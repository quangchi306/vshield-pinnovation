package com.trustnet.vshield.vpn

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import com.trustnet.vshield.network.LocalScraper
import com.trustnet.vshield.network.AiResultCache
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DnsVpnWorker(
    private val service: VShieldVpnService,
    private val tun: ParcelFileDescriptor
) {
    private val blocked = AtomicLong(0)
    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val inStream = FileInputStream(tun.fileDescriptor)
    private val outStream = FileOutputStream(tun.fileDescriptor)

    // Channel để chuyển tiếp các gói tin đến các worker xử lý
    private val packetChannel = Channel<ByteArray>(Channel.BUFFERED)

    // Số lượng worker xử lý song song – điều chỉnh dựa trên hiệu năng thiết bị
    private val workerCount = 4

    // Pool các DatagramSocket để forward DNS
    private val socketPool = DnsSocketPool(service, poolSize = 4)

    // 1. CHỐNG BÃO RETRY: Lưu các domain đang được cào data
    private val inProgressDomains = ConcurrentHashMap<String, Boolean>()

    // 2. TRẠM THU PHÍ: Giới hạn tối đa 4 luồng AI Scraper chạy cùng lúc để bảo vệ RAM
    private val aiScraperSemaphore = Semaphore(4)

    fun start() {
        // Khởi tạo pool socket
        socketPool.initialize()

        // Khởi tạo các worker coroutine (số lượng cố định)
        repeat(workerCount) {
            scope.launch {
                for (packet in packetChannel) {
                    processPacket(packet)
                }
            }
        }

        // Coroutine đọc dữ liệu từ TUN và gửi vào channel
        job = scope.launch {
            val buffer = ByteArray(32767)
            while (isActive && service.isRunning()) {
                try {
                    val length = inStream.read(buffer)
                    if (length > 0) {
                        val packetData = buffer.copyOf(length)
                        // Gửi vào channel – nếu channel đầy, send sẽ suspend, tạo backpressure tự nhiên
                        packetChannel.send(packetData)
                    }
                } catch (e: Exception) {
                    if (isActive) Log.e("DnsWorker", "Error reading packet: ${e.message}")
                }
            }
            packetChannel.close()
        }
    }

    fun stop() {
        job?.cancel()
        // Đóng channel để các worker kết thúc
        packetChannel.close()
        socketPool.close()
        try { inStream.close() } catch (_: Exception) {}
        try { outStream.close() } catch (_: Exception) {}
        scope.cancel()
    }

    // Hàm suspend để có thể gọi Semaphore.withPermit
    private suspend fun processPacket(packetData: ByteArray) {
        val udpPacket = UdpIpv4Packet.parse(packetData) ?: return

        if (udpPacket.dstPort != 53 || udpPacket.dstIp != VShieldVpnService.VPN_DNS_IP_INT) {
            return
        }

        val dnsPayload = udpPacket.payload
        val qDomain = DnsMessageParser.extractQueryDomain(dnsPayload) ?: return

        VpnStats.lastQueryDomain.postValue(qDomain)
        var responsePayload: ByteArray? = null

        // --- 1. CHỐT CHẶN 1: TÊN MIỀN ĐEN (Bị cấm bởi Local) ---
        if (DomainBlacklist.isBlocked(qDomain)) {
            val count = blocked.incrementAndGet()
            VpnStats.blockedCount.postValue(count)
            VpnStats.lastBlockedDomain.postValue(qDomain)

            mainHandler.post { service.openBlockingScreen(qDomain) }
            responsePayload = DnsMessageBuilder.buildNxdomainResponse(dnsPayload)
            Log.d("VShield", "BLOCKED BY LOCAL: $qDomain")
        }
        // --- 2. CHỐT CHẶN 2: TÊN MIỀN TRẮNG (An toàn tuyệt đối) ---
        else if (qDomain.endsWith(".arpa") || DomainBlacklist.isWhitelisted(qDomain)) {
            // Không log để tránh spam rác logcat, đi thẳng ra mạng luôn!
            responsePayload = forwardToUpstream(dnsPayload)
        }
        // --- 3. ĐƯA LÊN TRINH SÁT & AI PHÂN TÍCH ---
        else {
            var isBlockedByAi = AiResultCache.get(qDomain)

            if (isBlockedByAi == null) {
                // Đánh dấu đang xử lý, rớt luôn các truy vấn trùng lặp (DNS Retry)
                if (inProgressDomains.putIfAbsent(qDomain, true) != null) return

                try {
                    // Chờ qua trạm thu phí (chỉ cho 4 luồng chạy Jsoup cùng lúc)
                    aiScraperSemaphore.withPermit {
                        isBlockedByAi = LocalScraper.scrapeAndAnalyze(qDomain, service)
                        AiResultCache.put(qDomain, isBlockedByAi!!)
                    }
                } finally {
                    // Xong việc thì xóa khỏi danh sách in-progress
                    inProgressDomains.remove(qDomain)
                }
            }

            if (isBlockedByAi == true) {
                val count = blocked.incrementAndGet()
                VpnStats.blockedCount.postValue(count)
                VpnStats.lastBlockedDomain.postValue(qDomain)

                mainHandler.post { service.openBlockingScreen(qDomain) }
                responsePayload = DnsMessageBuilder.buildNxdomainResponse(dnsPayload)
                Log.d("VShield", "BLOCKED BY AI: $qDomain")
            } else {
                responsePayload = forwardToUpstream(dnsPayload)
            }
        }

        // Ghi phản hồi vào Interface trả về cho app
        if (responsePayload != null && responsePayload.isNotEmpty()) {
            val ipResponse = PacketBuilder.buildUdpIpv4Packet(
                srcIp = udpPacket.dstIp,
                dstIp = udpPacket.srcIp,
                srcPort = udpPacket.dstPort,
                dstPort = udpPacket.srcPort,
                payload = responsePayload
            )

            synchronized(outStream) {
                try { outStream.write(ipResponse) } catch (_: Exception) {}
            }
        }
    }

    private suspend fun forwardToUpstream(dnsQuery: ByteArray): ByteArray? {
        return withContext(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                // Lấy socket từ pool, nếu không có thì tạo tạm (fallback)
                socket = socketPool.borrowSocket(1000) // chờ tối đa 1s
                if (socket == null) {
                    // Tạo mới tạm thời nếu pool cạn
                    socket = DatagramSocket()
                    if (!service.protect(socket)) return@withContext null
                    socket.soTimeout = 2500
                }

                val upstreamAddr = InetSocketAddress(VShieldVpnService.UPSTREAM_DNS_IP, 53)
                val packet = DatagramPacket(dnsQuery, dnsQuery.size, upstreamAddr)
                socket.send(packet)

                val buf = ByteArray(4096)
                val response = DatagramPacket(buf, buf.size)
                socket.receive(response)

                buf.copyOf(response.length)
            } catch (e: Exception) {
                null
            } finally {
                socket?.let { socketPool.returnSocket(it) }
            }
        }
    }

    /**
     * Pool các DatagramSocket để tái sử dụng, giảm allocation.
     */
    private class DnsSocketPool(
        private val service: VShieldVpnService,
        private val poolSize: Int
    ) {
        private val queue: BlockingQueue<DatagramSocket> = LinkedBlockingQueue(poolSize)

        fun initialize() {
            repeat(poolSize) {
                createSocket()?.let { queue.offer(it) }
            }
        }

        private fun createSocket(): DatagramSocket? {
            return try {
                val socket = DatagramSocket()
                if (service.protect(socket)) {
                    socket.soTimeout = 2500
                    socket
                } else {
                    socket.close()
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

        // Mượn socket, chờ tối đa timeoutMs (milliseconds)
        fun borrowSocket(timeoutMs: Long): DatagramSocket? {
            return queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        }

        // Trả socket, nếu queue đầy thì đóng socket (không thêm vào)
        fun returnSocket(socket: DatagramSocket) {
            if (!queue.offer(socket)) {
                // Pool đã đầy, đóng socket để giải phóng tài nguyên
                try { socket.close() } catch (_: Exception) {}
            }
        }

        fun close() {
            queue.forEach { it.close() }
            queue.clear()
        }
    }
}