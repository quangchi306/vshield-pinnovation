package com.trustnet.vshield.vpn.packet

object Checksums {
    fun ipv4HeaderChecksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = 0
        while (i < length) {
            val hi = buf[offset + i].toInt() and 0xFF
            val lo = if (i + 1 < length) buf[offset + i + 1].toInt() and 0xFF else 0
            sum += (hi shl 8) or lo
            i += 2
        }
        sum = fold(sum)
        return sum.inv() and 0xFFFF
    }

    fun udpChecksumIpv4(srcIp: Int, dstIp: Int, udpPacket: ByteArray, udpOffset: Int, udpLength: Int): Int {
        var sum = 0

        // Pseudo-header
        sum += (srcIp ushr 16) and 0xFFFF
        sum += srcIp and 0xFFFF
        sum += (dstIp ushr 16) and 0xFFFF
        sum += dstIp and 0xFFFF
        sum += 0x0011 // protocol UDP (17)
        sum += udpLength and 0xFFFF

        // UDP header + payload
        var i = 0
        while (i < udpLength) {
            val hi = udpPacket[udpOffset + i].toInt() and 0xFF
            val lo = if (i + 1 < udpLength) udpPacket[udpOffset + i + 1].toInt() and 0xFF else 0
            sum += (hi shl 8) or lo
            i += 2
        }

        sum = fold(sum)
        val checksum = sum.inv() and 0xFFFF
        return if (checksum == 0) 0xFFFF else checksum
    }

    private fun fold(x: Int): Int {
        var sum = x
        while ((sum ushr 16) != 0) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return sum
    }
}
