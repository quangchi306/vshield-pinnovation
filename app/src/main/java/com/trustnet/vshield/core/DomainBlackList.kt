package com.trustnet.vshield.core

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object DomainBlacklist {
    private const val TAG = "DomainBlacklist"

    // Tên các file binary (được tải về từ Server) lưu trong FilesDir
    private const val FILE_WHITELIST_BIN = "whitelist.bin"
    private const val FILE_PHISHING_BIN = "phishing.bin"
    private const val FILE_ADULT_BIN = "adult.bin"
    private const val FILE_GAMBLING_BIN = "gambling.bin"

    // Dung lượng tối đa dự kiến của mỗi mảng (1 triệu)
    private const val CAPACITY = 1_000_000
    private const val FPR = 0.0000001

    @Volatile var blockAdult: Boolean = true
    @Volatile var blockGambling: Boolean = true

    // Whitelist tạm thời (Bypass trong 5 phút) - Giữ nguyên logic cũ của bạn
    private val tempWhitelist = ConcurrentHashMap<String, Long>()

    // Thay thế HashSet bằng BloomFilter cho Whitelist
    private var whitelistFilter: BloomFilter? = null

    // Blacklist Filters
    private var phishingFilter: BloomFilter? = null
    private var adultFilter: BloomFilter? = null
    private var gamblingFilter: BloomFilter? = null

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        Log.i(TAG, "Đang khởi tạo bộ lọc từ Binary Bloom Filters...")

        whitelistFilter = loadBinaryFilter(context, FILE_WHITELIST_BIN)
        phishingFilter = loadBinaryFilter(context, FILE_PHISHING_BIN)
        adultFilter = loadBinaryFilter(context, FILE_ADULT_BIN)
        gamblingFilter = loadBinaryFilter(context, FILE_GAMBLING_BIN)

        Log.i(TAG, "Khởi tạo hoàn tất.")
    }

    fun addTemporaryAllow(domain: String) {
        val cleanDomain = normalizeDomain(domain) ?: return
        tempWhitelist[cleanDomain] = System.currentTimeMillis() + 300_000
        Log.i(TAG, "Đã bỏ chặn tạm thời: $cleanDomain")
    }

    /**
     * Thuật toán kiểm tra Whitelist cho BloomFilter (Chẻ nhỏ tên miền)
     */
    fun isWhitelisted(domain: String): Boolean {
        val cleanDomain = normalizeDomain(domain) ?: return false

        // Do Bloom Filter không hỗ trợ endsWith, ta tách domain ra để kiểm tra
        // VD: sub.api.google.com -> Sẽ check lần lượt:
        // 1. sub.api.google.com
        // 2. api.google.com
        // 3. google.com
        val parts = cleanDomain.split(".")

        for (i in 0 until parts.size - 1) {
            val domainToCheck = parts.subList(i, parts.size).joinToString(".")
            if (whitelistFilter?.mightContain(domainToCheck) == true) {
                return true
            }
        }
        return false
    }

    fun isBlocked(domain: String): Boolean {
        val cleanDomain = normalizeDomain(domain) ?: return false

        // 1. Check Whitelist gốc
        if (isWhitelisted(cleanDomain)) return false

        // 2. Check Whitelist tạm thời (Do người dùng bấm "Vẫn truy cập")
        val expiryTime = tempWhitelist[cleanDomain]
        if (expiryTime != null) {
            if (System.currentTimeMillis() < expiryTime) {
                return false
            } else {
                tempWhitelist.remove(cleanDomain)
            }
        }

        // 3. Check Phishing (Luôn chặn)
        if (phishingFilter?.mightContain(cleanDomain) == true) {
            Log.w(TAG, "BLOCKED [Phishing]: $cleanDomain")
            return true
        }

        // 4. Check Adult
        if (blockAdult && adultFilter?.mightContain(cleanDomain) == true) {
            Log.w(TAG, "BLOCKED [Adult]: $cleanDomain")
            return true
        }

        // 5. Check Gambling
        if (blockGambling && gamblingFilter?.mightContain(cleanDomain) == true) {
            Log.w(TAG, "BLOCKED [Gambling]: $cleanDomain")
            return true
        }

        return false
    }

    private fun loadBinaryFilter(context: Context, fileName: String): BloomFilter {
        val file = File(context.filesDir, fileName)
        return if (file.exists()) {
            try {
                val bytes = file.readBytes()
                BloomFilter.loadFromByteArray(bytes, CAPACITY, FPR)
            } catch (e: Exception) {
                Log.e(TAG, "Lỗi đọc file $fileName: ${e.message}")
                BloomFilter.create(CAPACITY, FPR) // Fallback tạo mới nếu lỗi
            }
        } else {
            // Khởi tạo mảng trống nếu chưa tải từ server về
            BloomFilter.create(CAPACITY, FPR)
        }
    }

    private fun normalizeDomain(raw: String): String? {
        if (raw.trim().startsWith("#") || raw.isBlank()) return null
        var s = raw.trim().lowercase(Locale.ROOT)
        if (s.contains("://")) s = s.substringAfter("://")
        if (s.contains("/")) s = s.substringBefore("/")
        s = s.removePrefix("www.").trimEnd('.')
        if (s.length < 3 || !s.contains(".")) return null
        return s
    }
}