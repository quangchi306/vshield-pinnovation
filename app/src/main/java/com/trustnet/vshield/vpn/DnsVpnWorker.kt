package com.trustnet.vshield.vpn

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import com.trustnet.vshield.VShieldVpnService
import com.trustnet.vshield.core.DomainBlacklist
import com.trustnet.vshield.core.VpnStats
import com.trustnet.vshield.network.AiResultCache
import com.trustnet.vshield.network.LocalScraper
import com.trustnet.vshield.vpn.dns.DnsMessageBuilder
import com.trustnet.vshield.vpn.dns.DnsMessageParser
import com.trustnet.vshield.vpn.packet.PacketBuilder
import com.trustnet.vshield.vpn.packet.UdpIpv4Packet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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

    private val packetChannel = Channel<ByteArray>(Channel.BUFFERED)
    private val workerCount = 4

    private val socketPool = DnsSocketPool(service, poolSize = 4)

    // chống spam scrape cùng 1 domain
    private val inProgressDomains = ConcurrentHashMap<String, Boolean>()

    // giới hạn số scraper chạy song song để bảo vệ RAM
    private val aiScraperSemaphore = Semaphore(4)

    fun start() {
        socketPool.initialize()

        repeat(workerCount) {
            scope.launch {
                for (packet in packetChannel) {
                    processPacket(packet)
                }
            }
        }

        job = scope.launch {
            val buffer = ByteArray(32767)

            while (isActive && service.isRunning()) {
                try {
                    val length = inStream.read(buffer)
                    if (length > 0) {
                        val packetData = buffer.copyOf(length)
                        packetChannel.send(packetData)
                    }
                } catch (e: Exception) {
                    if (isActive) {
                        Log.e("DnsWorker", "Error reading packet: ${e.message}")
                    }
                }
            }

            packetChannel.close()
        }
    }

    fun stop() {
        job?.cancel()
        packetChannel.close()
        socketPool.close()

        try { inStream.close() } catch (_: Exception) {}
        try { outStream.close() } catch (_: Exception) {}

        scope.cancel()
    }

    private suspend fun processPacket(packetData: ByteArray) {
        val udpPacket = UdpIpv4Packet.parse(packetData) ?: return

        if (udpPacket.dstPort != 53 || udpPacket.dstIp != VShieldVpnService.VPN_DNS_IP_INT) {
            return
        }

        val dnsPayload = udpPacket.payload
        val qDomain = DnsMessageParser.extractQueryDomain(dnsPayload) ?: return

        VpnStats.lastQueryDomain.postValue(qDomain)

        var responsePayload: ByteArray? = null

        // 1. chặn theo blacklist local trước
        when (DomainBlacklist.getBlockCategory(qDomain)) {
            DomainBlacklist.BlockCategory.PHISHING -> {
                val count = blocked.incrementAndGet()
                VpnStats.blockedCount.postValue(count)
                VpnStats.lastBlockedDomain.postValue(qDomain)

                responsePayload = DnsMessageBuilder.buildNxdomainResponse(dnsPayload)
                Log.d("VShield", "BLOCKED [PHISHING]: $qDomain")
            }

            DomainBlacklist.BlockCategory.ADULT,
            DomainBlacklist.BlockCategory.GAMBLING -> {
                val count = blocked.incrementAndGet()
                VpnStats.blockedCount.postValue(count)
                VpnStats.lastBlockedDomain.postValue(qDomain)

                mainHandler.post {
                    service.openBlockingScreen(qDomain)
                }

                responsePayload = DnsMessageBuilder.buildNxdomainResponse(dnsPayload)
                Log.d("VShield", "WARN/BLOCKED [CONTENT]: $qDomain")
            }

            DomainBlacklist.BlockCategory.NONE -> {
                // không làm gì ở đây, xử lý tiếp bên dưới
            }
        }

        // 2. nếu đã có quyết định chặn local thì trả luôn
        if (responsePayload == null) {
            if (qDomain.endsWith(".arpa")) {
                responsePayload = forwardToUpstream(dnsPayload)
            } else {
                val isBlockedByAi = AiResultCache.get(qDomain)

                when (isBlockedByAi) {
                    true -> {
                        val count = blocked.incrementAndGet()
                        VpnStats.blockedCount.postValue(count)
                        VpnStats.lastBlockedDomain.postValue(qDomain)

                        mainHandler.post {
                            service.openBlockingScreen(qDomain)
                        }

                        responsePayload = DnsMessageBuilder.buildNxdomainResponse(dnsPayload)
                        Log.d("VShield", "BLOCKED BY AI (Cache hit): $qDomain")
                    }

                    false -> {
                        responsePayload = forwardToUpstream(dnsPayload)
                    }

                    null -> {
                        // chưa có kết quả AI -> cho qua trước
                        responsePayload = forwardToUpstream(dnsPayload)

                        // scrape nền nếu chưa scrape domain này
                        if (inProgressDomains.putIfAbsent(qDomain, true) == null) {
                            scope.launch {
                                try {
                                    aiScraperSemaphore.withPermit {
                                        Log.d("VShield", "🔍 Đang trinh sát ngầm: $qDomain")
                                        val isBad = LocalScraper.scrapeAndAnalyze(qDomain, service)
                                        AiResultCache.put(qDomain, isBad)

                                        if (isBad) {
                                            Log.w("VShield", "🚨 PHÁT HIỆN TRỄ (LATE BLOCK): $qDomain")
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("VShield", "Scraper error for $qDomain: ${e.message}")
                                } finally {
                                    inProgressDomains.remove(qDomain)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. ghi phản hồi về lại TUN
        if (responsePayload != null && responsePayload.isNotEmpty()) {
            val ipResponse = PacketBuilder.buildUdpIpv4Packet(
                srcIp = udpPacket.dstIp,
                dstIp = udpPacket.srcIp,
                srcPort = udpPacket.dstPort,
                dstPort = udpPacket.srcPort,
                payload = responsePayload
            )

            synchronized(outStream) {
                try {
                    outStream.write(ipResponse)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun forwardToUpstream(dnsQuery: ByteArray): ByteArray? {
        var socket: DatagramSocket? = null

        return try {
            socket = socketPool.borrowSocket(1000)

            if (socket == null) {
                socket = DatagramSocket()
                if (!service.protect(socket)) return null
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

        fun borrowSocket(timeoutMs: Long): DatagramSocket? {
            return queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        }

        fun returnSocket(socket: DatagramSocket) {
            if (!queue.offer(socket)) {
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
        }

        fun close() {
            queue.forEach {
                try {
                    it.close()
                } catch (_: Exception) {
                }
            }
            queue.clear()
        }
    }
}