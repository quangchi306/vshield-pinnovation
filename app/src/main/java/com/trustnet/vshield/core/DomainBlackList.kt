    package com.trustnet.vshield.core

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Quản lý blacklist dạng domain (đã normalize).
 * - Load sample từ assets lần đầu
 * - Có thể update bằng cách download 1 file .txt (URLs hoặc domains) rồi trích host/domain.
 */
object DomainBlacklist {
    private const val TAG = "DomainBlacklist"
    private const val STORED_FILE = "blacklist_domains.txt"

    const val DEFAULT_REMOTE_URL =
        "https://raw.githubusercontent.com/elliotwutingfeng/ChongLuaDao-Phishing-Blocklist/main/urls.txt"

    private lateinit var appContext: Context

    private data class Snapshot(
        val domains: Set<String>,
        val bloom: BloomFilter
    )

    private val snapshotRef = AtomicReference(Snapshot(emptySet(), BloomFilter.empty()))
    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        appContext = context.applicationContext
        val file = File(appContext.filesDir, STORED_FILE)

        val domains = if (file.exists()) {
            file.bufferedReader().useLines { seq ->
                seq.mapNotNull { normalizeDomain(it) }.toSet()
            }
        } else {
            appContext.assets.open("blocklist_sample.txt").bufferedReader().useLines { seq ->
                seq.mapNotNull { normalizeDomain(it) }.toSet()
            }
        }

        applyNewDomains(domains)
        Log.i(TAG, "Initialized with ${domains.size} domains")
    }

    fun isBlocked(domain: String): Boolean {
        val d0 = normalizeDomain(domain) ?: return false
        val snap = snapshotRef.get()

        // Check exact + parent domains (subdomain blocking)
        var d = d0
        while (true) {
            // Bloom-filter gate (nhanh) rồi confirm bằng HashSet (chắc chắn)
            if (snap.bloom.mightContain(d) && snap.domains.contains(d)) return true

            val idx = d.indexOf('.')
            if (idx < 0) break
            d = d.substring(idx + 1)
        }
        return false
    }

    fun updateFromUrl(url: String = DEFAULT_REMOTE_URL, callback: (ok: Boolean, msg: String) -> Unit) {
        Thread {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                }

                val code = conn.responseCode
                if (code !in 200..299) {
                    callback(false, "HTTP $code khi tải blacklist")
                    return@Thread
                }

                val domains = conn.inputStream.bufferedReader().useLines { seq ->
                    seq.mapNotNull { extractDomainFromLine(it) }
                        .mapNotNull { normalizeDomain(it) }
                        .toSet()
                }

                // Persist domain-only list
                val file = File(appContext.filesDir, STORED_FILE)
                file.bufferedWriter().use { out ->
                    domains.forEach { out.appendLine(it) }
                }

                applyNewDomains(domains)
                callback(true, "Cập nhật thành công: ${domains.size} domains")
            } catch (e: Exception) {
                callback(false, "Update lỗi: ${e.message}")
            }
        }.start()
    }

    private fun applyNewDomains(domains: Set<String>) {
        val bloom = BloomFilter.create(expectedInsertions = domains.size, falsePositiveRate = 1e-3)
        domains.forEach { bloom.add(it) }
        snapshotRef.set(Snapshot(domains, bloom))
    }

    private fun extractDomainFromLine(line: String): String? {
        var s = line.trim()
        if (s.isEmpty()) return null
        if (s.startsWith("#") || s.startsWith("!") || s.startsWith("[")) return null

        // Nếu dạng hosts file: "0.0.0.0 domain"
        val parts = s.split(Regex("\\s+"))
        if (parts.size >= 2 && looksLikeIp(parts[0])) {
            s = parts.last()
        }

        // uBlock: ||domain^
        if (s.startsWith("||")) s = s.removePrefix("||")
        s = s.trimEnd('^')

        // URL?
        if (s.contains("://")) {
            return try {
                URI(s).host
            } catch (_: Exception) {
                null
            }
        }

        if (s.contains("/")) {
            return try {
                URI("http://$s").host
            } catch (_: Exception) {
                s.substringBefore("/")
            }
        }

        return s
    }

    private fun looksLikeIp(s: String): Boolean = s.count { it == '.' } == 3 && s.all { it.isDigit() || it == '.' }

    private fun normalizeDomain(raw: String): String? {
        val s0 = raw.trim()
        if (s0.isEmpty()) return null
        if (s0.startsWith("#") || s0.startsWith("!")) return null

        var s = s0.lowercase(Locale.ROOT)
        s = s.removePrefix("*.").removePrefix(".").trimEnd('.')

        // Bỏ port nếu có
        if (s.contains(":")) s = s.substringBefore(":")

        // Filter sơ bộ
        if (!s.contains(".")) return null
        if (s.length < 4) return null
        if (s.any { it.isWhitespace() }) return null
        return s
    }
    const val FILTER_SIZE_M = 2_000_000
    const val FILTER_HASH_K = 5

    // URL tải file BIN
    const val BINARY_REMOTE_URL = "https://your-server.com/api/v1/blacklist.bin"

    fun updateFromBinary(url: String = BINARY_REMOTE_URL, callback: (Boolean, String) -> Unit) {
        Thread {
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection)
                // ... setup connection ...

                // Đọc file binary
                val bytes = conn.inputStream.readBytes()

                if (bytes.isEmpty()) {
                    callback(false, "File binary rỗng")
                    return@Thread
                }

                // Lưu file binary xuống đĩa để cache
                val file = File(appContext.filesDir, "blacklist.bin")
                file.writeBytes(bytes)

                // Load vào bộ nhớ
                val newBloom = BloomFilter.loadFromByteArray(bytes, FILTER_HASH_K, FILTER_SIZE_M)

                // Cập nhật snapshot
                snapshotRef.set(Snapshot(emptySet(), newBloom))

                callback(true, "Đã cập nhật dữ liệu vệ tinh (Binary size: ${bytes.size} bytes)")
            } catch (e: Exception) {
                callback(false, "Lỗi update: ${e.message}")
            }
        }.start()
    }
}
