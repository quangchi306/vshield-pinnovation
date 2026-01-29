package com.trustnet.vshield.vpn.dns

import java.util.Locale

object DnsMessageParser {
    fun extractQueryDomain(dns: ByteArray): String? {
        if (dns.size < 12) return null

        val name = readName(dns, 12, depth = 0).first ?: return null
        val domain = name.lowercase(Locale.ROOT).trimEnd('.')
        return if (domain.isBlank()) null else domain
    }

    private fun readName(msg: ByteArray, start: Int, depth: Int): Pair<String?, Int> {
        if (depth > 5) return null to start
        var i = start
        val labels = mutableListOf<String>()

        while (i < msg.size) {
            val len = msg[i].toInt() and 0xFF
            if (len == 0) {
                i += 1
                break
            }

            // Pointer?
            if ((len and 0xC0) == 0xC0) {
                if (i + 1 >= msg.size) return null to i + 1
                val b2 = msg[i + 1].toInt() and 0xFF
                val ptr = ((len and 0x3F) shl 8) or b2
                val (pName, _) = readName(msg, ptr, depth + 1)
                if (pName != null) labels.addAll(pName.split(".").filter { it.isNotBlank() })
                i += 2
                break
            }

            val next = i + 1 + len
            if (next > msg.size) return null to msg.size
            val label = String(msg, i + 1, len, Charsets.UTF_8)
            labels.add(label)
            i = next
        }

        return labels.joinToString(".") to i
    }
}
