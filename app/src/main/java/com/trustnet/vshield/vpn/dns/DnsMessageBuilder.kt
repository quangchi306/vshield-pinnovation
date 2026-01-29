package com.trustnet.vshield.vpn.dns

object DnsMessageBuilder {
    fun buildNxdomainResponse(query: ByteArray): ByteArray {
        if (query.size < 12) return ByteArray(0)

        val resp = query.copyOf()

        val qFlags = ((query[2].toInt() and 0xFF) shl 8) or (query[3].toInt() and 0xFF)
        val rdBit = qFlags and 0x0100

        // QR=1 (0x8000), RA=1 (0x0080), giữ RD theo query, RCODE=3
        val rFlags = 0x8000 or 0x0080 or rdBit or 0x0003
        val resp2 = resp.copyOf()
        resp2[2] = ((rFlags ushr 8) and 0xFF).toByte()
        resp2[3] = (rFlags and 0xFF).toByte()

        // ANCOUNT = 0
        resp2[6] = 0
        resp2[7] = 0
        // NSCOUNT = 0
        resp2[8] = 0
        resp2[9] = 0

        return resp2
    }
}
