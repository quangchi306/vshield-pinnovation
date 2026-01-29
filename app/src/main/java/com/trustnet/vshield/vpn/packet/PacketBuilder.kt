package com.trustnet.vshield.vpn.packet

object PacketBuilder {
    fun buildUdpIpv4Packet(
        srcIp: Int,
        dstIp: Int,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        val ipHeaderLen = 20
        val udpLen = 8 + payload.size
        val totalLen = ipHeaderLen + udpLen

        val p = ByteArray(totalLen)

        // IPv4 header
        p[0] = 0x45.toByte() // Version=4, IHL=5
        p[1] = 0
        writeU16(p, 2, totalLen)
        writeU16(p, 4, (System.nanoTime().toInt() and 0xFFFF))
        writeU16(p, 6, 0)
        p[8] = 64
        p[9] = 17 // UDP
        writeU16(p, 10, 0)
        writeU32(p, 12, srcIp)
        writeU32(p, 16, dstIp)

        val ipCsum = Checksums.ipv4HeaderChecksum(p, 0, ipHeaderLen)
        writeU16(p, 10, ipCsum)

        // UDP header
        val u = ipHeaderLen
        writeU16(p, u, srcPort)
        writeU16(p, u + 2, dstPort)
        writeU16(p, u + 4, udpLen)
        writeU16(p, u + 6, 0)

        // Payload
        payload.copyInto(p, destinationOffset = u + 8)

        val udpCsum = Checksums.udpChecksumIpv4(srcIp, dstIp, p, u, udpLen)
        writeU16(p, u + 6, udpCsum)

        return p
    }

    private fun writeU16(buf: ByteArray, off: Int, v: Int) {
        buf[off] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 1] = (v and 0xFF).toByte()
    }

    private fun writeU32(buf: ByteArray, off: Int, v: Int) {
        buf[off] = ((v ushr 24) and 0xFF).toByte()
        buf[off + 1] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 3] = (v and 0xFF).toByte()
    }
}
