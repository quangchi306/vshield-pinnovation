package com.trustnet.vshield.vpn.packet

data class UdpIpv4Packet(
    val srcIp: Int,
    val dstIp: Int,
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray
) {
    companion object {
        fun parse(packet: ByteArray): UdpIpv4Packet? {
            if (packet.size < 28) return null

            val vihl = packet[0].toInt() and 0xFF
            val version = (vihl ushr 4) and 0x0F
            if (version != 4) return null

            val ihlBytes = (vihl and 0x0F) * 4
            if (ihlBytes < 20 || packet.size < ihlBytes + 8) return null

            val proto = packet[9].toInt() and 0xFF
            if (proto != 17) return null // UDP only

            val srcIp = ipv4ToInt(packet, 12)
            val dstIp = ipv4ToInt(packet, 16)

            val udpOff = ihlBytes
            val srcPort = u16(packet[udpOff], packet[udpOff + 1])
            val dstPort = u16(packet[udpOff + 2], packet[udpOff + 3])
            val udpLen = u16(packet[udpOff + 4], packet[udpOff + 5])
            if (udpLen < 8) return null

            val payloadOff = udpOff + 8
            val payloadLen = udpLen - 8
            if (payloadOff + payloadLen > packet.size) return null

            val payload = packet.copyOfRange(payloadOff, payloadOff + payloadLen)
            return UdpIpv4Packet(srcIp, dstIp, srcPort, dstPort, payload)
        }

        fun ipStringToInt(ip: String): Int {
            val p = ip.trim().split(".")
            require(p.size == 4)
            return (p[0].toInt() shl 24) or
                    (p[1].toInt() shl 16) or
                    (p[2].toInt() shl 8) or
                    (p[3].toInt())
        }

        fun intToIpString(ip: Int): String {
            val b1 = (ip ushr 24) and 0xFF
            val b2 = (ip ushr 16) and 0xFF
            val b3 = (ip ushr 8) and 0xFF
            val b4 = ip and 0xFF
            return "$b1.$b2.$b3.$b4"
        }

        private fun ipv4ToInt(buf: ByteArray, off: Int): Int {
            return ((buf[off].toInt() and 0xFF) shl 24) or
                    ((buf[off + 1].toInt() and 0xFF) shl 16) or
                    ((buf[off + 2].toInt() and 0xFF) shl 8) or
                    (buf[off + 3].toInt() and 0xFF)
        }

        private fun u16(b1: Byte, b2: Byte): Int =
            ((b1.toInt() and 0xFF) shl 8) or (b2.toInt() and 0xFF)
    }
}