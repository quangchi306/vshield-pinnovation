package com.trustnet.vshield.vpn.dns

import com.trustnet.vshield.VShieldVpnService
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.random.Random

object DnsTestClient {

    fun testA(domain: String): String {
        val q = buildQueryA(domain)
        val dnsIp = InetAddress.getByName(VShieldVpnService.VPN_DNS_IP)

        DatagramSocket().use { s ->
            s.soTimeout = 5000

            val req = DatagramPacket(q, q.size, dnsIp, 53)
            s.send(req)

            val buf = ByteArray(1500)
            val resp = DatagramPacket(buf, buf.size)
            s.receive(resp)

            val msg = buf.copyOf(resp.length)
            val rcode = msg[3].toInt() and 0x0F
            return when (rcode) {
                0 -> "OK (RCODE=0). Có vẻ domain không bị chặn."
                3 -> "BỊ CHẶN (NXDOMAIN)."
                else -> "Có phản hồi nhưng RCODE=$rcode"
            }
        }
    }

    private fun buildQueryA(domain: String): ByteArray {
        val id = Random.nextInt(0, 65536)
        val header = ByteArray(12)

        header[0] = ((id ushr 8) and 0xFF).toByte()
        header[1] = (id and 0xFF).toByte()
        // flags: RD=1
        header[2] = 0x01
        header[3] = 0x00
        // QDCOUNT=1
        header[4] = 0x00
        header[5] = 0x01

        val qname = encodeQName(domain)
        val qtypeClass = byteArrayOf(
            0x00, 0x01, // QTYPE=A
            0x00, 0x01  // QCLASS=IN
        )
        return header + qname + qtypeClass
    }

    private fun encodeQName(domain: String): ByteArray {
        val labels = domain.trim().trimEnd('.').split(".").filter { it.isNotBlank() }
        val out = ArrayList<Byte>()
        for (l in labels) {
            val b = l.toByteArray(Charsets.UTF_8)
            out.add((b.size and 0xFF).toByte())
            for (x in b) out.add(x)
        }
        out.add(0x00)
        return out.toByteArray()
    }
}
