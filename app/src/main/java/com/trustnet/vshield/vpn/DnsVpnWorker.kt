package com.trustnet.vshield.vpn

import android.util.Log
import com.trustnet.vshield.VShieldVpnService
import com.trustnet.vshield.core.BlockedDomainLog
import com.trustnet.vshield.core.DomainBlacklist
import com.trustnet.vshield.core.VpnStats
import com.trustnet.vshield.network.AiResult
import com.trustnet.vshield.network.AiResultCache
import com.trustnet.vshield.network.LocalScraper
import com.trustnet.vshield.vpn.dns.DnsMessageBuilder
import com.trustnet.vshield.vpn.dns.DnsMessageParser
import com.trustnet.vshield.vpn.packet.PacketBuilder
import com.trustnet.vshield.vpn.packet.UdpIpv4Packet
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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

    private val inStream  = FileInputStream(tun.fileDescriptor)
    private val outStream = FileOutputStream(tun.fileDescriptor)

    private val packetChannel = Channel<ByteArray>(Channel.BUFFERED)

    private val workerCount = 10

    private val socketPool = DnsSocketPool(service, poolSize = 10)

    private val aiTasks = ConcurrentHashMap<String, Deferred<AiResult>>()

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
            if (!DomainBlacklist.isListsReady()) {
                Log.w("DnsWorker", "Lists chưa sẵn sàng, tạm dừng đọc TUN...")
                val deadline = System.currentTimeMillis() + MAX_LISTS_WAIT_MS
                while (!DomainBlacklist.isListsReady()
                    && System.currentTimeMillis() < deadline
                    && isActive
                ) {
                    delay(50)
                }
                Log.i("DnsWorker", "Lists ready=${DomainBlacklist.isListsReady()}, bắt đầu xử lý traffic")
            }

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

        try { inStream.close() }  catch (_: Exception) {}
        try { outStream.close() } catch (_: Exception) {}

        scope.cancel()
    }

    private fun normalizeForCache(domain: String): String =
        domain.removePrefix("www.").lowercase().trim()

    private suspend fun processPacket(packetData: ByteArray) {
        val udpPacket = UdpIpv4Packet.parse(packetData) ?: return

        if (udpPacket.dstPort != 53 || udpPacket.dstIp != VShieldVpnService.VPN_DNS_IP_INT) {
            return
        }

        val dnsPayload = udpPacket.payload
        val qDomain    = DnsMessageParser.extractQueryDomain(dnsPayload) ?: return

        VpnStats.lastQueryDomain.postValue(qDomain)

        var responsePayload: ByteArray? = null
        var shouldCheckAi = false

        when (DomainBlacklist.getBlockCategory(qDomain)) {

            DomainBlacklist.BlockCategory.PHISHING -> {
                val count = blocked.incrementAndGet()
                VpnStats.blockedCount.postValue(count)
                VpnStats.lastBlockedDomain.postValue(qDomain)
                BlockedDomainLog.add(
                    domain = qDomain,
                    reason = "Lừa đảo & Mã độc",
                    source = BlockedDomainLog.Source.BLACKLIST
                )
                responsePayload = DnsMessageBuilder.buildNxdomainResponse(dnsPayload)
                Log.d("VShield", "BLOCKED [PHISHING]: $qDomain")
            }

            DomainBlacklist.BlockCategory.ADULT,
            DomainBlacklist.BlockCategory.GAMBLING -> {
                val count = blocked.incrementAndGet()
                VpnStats.blockedCount.postValue(count)
                VpnStats.lastBlockedDomain.postValue(qDomain)
                val reason = when (DomainBlacklist.getBlockCategory(qDomain)) {
                    DomainBlacklist.BlockCategory.ADULT    -> "Nội dung 18+"
                    DomainBlacklist.BlockCategory.GAMBLING -> "Cờ bạc"
                    else                                   -> "Nội dung bị hạn chế"
                }
                BlockedDomainLog.add(
                    domain = qDomain,
                    reason = reason,
                    source = BlockedDomainLog.Source.BLACKLIST
                )
                responsePayload = DnsMessageBuilder.buildNxdomainResponse(dnsPayload)
                Log.d("VShield", "BLOCKED [CONTENT]: $qDomain")
            }

            DomainBlacklist.BlockCategory.WHITELIST -> {
                responsePayload = forwardToUpstream(dnsPayload)
            }

            DomainBlacklist.BlockCategory.NONE -> {
                shouldCheckAi = true
            }
        }

        if (shouldCheckAi) {
            if (qDomain.endsWith(".arpa")) {
                responsePayload = forwardToUpstream(dnsPayload)
            } else {
                val cacheKey   = normalizeForCache(qDomain)
                val aiDecision = AiResultCache.get(cacheKey)

                if (aiDecision != null) {
                    if (aiDecision.isBlocked) {
                        val count = blocked.incrementAndGet()
                        VpnStats.blockedCount.postValue(count)
                        VpnStats.lastBlockedDomain.postValue(qDomain)
                        BlockedDomainLog.add(
                            domain = qDomain,
                            reason = aiDecision.reason,
                            source = BlockedDomainLog.Source.AI
                        )
                        responsePayload = DnsMessageBuilder.buildNxdomainResponse(dnsPayload)
                        Log.d("VShield", "BLOCKED BY AI (Cache hit): $qDomain")
                    } else {
                        responsePayload = forwardToUpstream(dnsPayload)
                    }
                } else {
                    Log.d("VShield", "⏳ Đang treo gói tin để chờ AI phân tích: $qDomain")

                    val deferredTask = aiTasks.computeIfAbsent(cacheKey) {
                        scope.async {
                            var aiRes = AiResult(false)
                            try {
                                aiScraperSemaphore.withPermit {
                                    aiRes = LocalScraper.scrapeAndAnalyze(qDomain, service)
                                }
                                AiResultCache.put(cacheKey, aiRes.isMalicious, aiRes.reason)
                            } catch (e: Exception) {
                                Log.e("VShield", "Lỗi Scraper cho $qDomain: ${e.message}")
                                AiResultCache.put(cacheKey, false, "")
                            }
                            aiRes
                        }
                    }

                    val aiResult = deferredTask.await()
                    aiTasks.remove(cacheKey)

                    if (aiResult.isMalicious) {
                        val count = blocked.incrementAndGet()
                        VpnStats.blockedCount.postValue(count)
                        VpnStats.lastBlockedDomain.postValue(qDomain)
                        BlockedDomainLog.add(
                            domain = qDomain,
                            reason = aiResult.reason,
                            source = BlockedDomainLog.Source.AI
                        )
                        responsePayload = DnsMessageBuilder.buildNxdomainResponse(dnsPayload)
                        Log.d("VShield", "🚫 BLOCKED BY AI (Realtime): $qDomain - ${aiResult.reason}")
                    } else {
                        responsePayload = forwardToUpstream(dnsPayload)
                        Log.d("VShield", "✅ SAFE (Realtime): $qDomain")
                    }
                }
            }
        }

        if (responsePayload != null && responsePayload.isNotEmpty()) {
            val ipResponse = PacketBuilder.buildUdpIpv4Packet(
                srcIp   = udpPacket.dstIp,
                dstIp   = udpPacket.srcIp,
                srcPort = udpPacket.dstPort,
                dstPort = udpPacket.srcPort,
                payload = responsePayload
            )

            synchronized(outStream) {
                try {
                    outStream.write(ipResponse)
                } catch (_: Exception) {}
            }
        }
    }

    private fun forwardToUpstream(dnsQuery: ByteArray): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            socket = socketPool.borrowSocket(2000)
            if (socket == null) {
                socket = DatagramSocket()
                if (!service.protect(socket)) return null
                socket.soTimeout = 2500
            }

            val upstreamAddr = InetSocketAddress(VShieldVpnService.UPSTREAM_DNS_IP, 53)
            val packet       = DatagramPacket(dnsQuery, dnsQuery.size, upstreamAddr)
            socket.send(packet)

            val buf      = ByteArray(4096)
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
            } catch (e: Exception) { null }
        }

        fun borrowSocket(timeoutMs: Long): DatagramSocket? {
            var socket = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
            if (socket == null || socket.isClosed) {
                socket = createSocket()
            }
            return socket
        }

        fun returnSocket(socket: DatagramSocket) {
            if (!socket.isClosed && !queue.offer(socket)) {
                try { socket.close() } catch (_: Exception) {}
            }
        }

        fun close() {
            while (queue.isNotEmpty()) {
                try { queue.poll()?.close() } catch (_: Exception) {}
            }
        }
    }

    companion object {
        // Giảm thời gian chờ từ 10s xuống 2s vì VpnService đã có cơ chế chờ riêng
        private const val MAX_LISTS_WAIT_MS = 2_000L
    }
}