package com.trustnet.vshield.core

import android.content.Context
import android.util.Log
import java.io.File
import java.util.HashSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object DomainBlacklist {
    private const val TAG = "DomainBlacklist"

    // Tên các file trong thư mục assets
    private const val FILE_WHITELIST = "whitelist.txt"
    private const val FILE_PHISHING = "blacklist_phishing.txt"
    private const val FILE_ADULT = "blacklist_adult.txt"
    private const val FILE_GAMBLING = "blacklist_gambling.txt"

    // Cấu hình chặn (User bật/tắt)
    @Volatile var blockAdult: Boolean = true
    @Volatile var blockGambling: Boolean = true

    // Whitelist gốc (HashSet)
    private val whitelist = HashSet<String>()

    // Whitelist tạm thời (Bypass trong 5 phút) - Dùng ConcurrentHashMap để an toàn đa luồng
    private val tempWhitelist = ConcurrentHashMap<String, Long>()

    // Blacklist: 3 Bloom Filter
    private var phishingFilter: BloomFilter? = null
    private var adultFilter: BloomFilter? = null
    private var gamblingFilter: BloomFilter? = null

    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        Log.i(TAG, "Đang khởi tạo bộ lọc...")

        // 1. Load Whitelist
        loadWhitelist(context)

        // 2. Load Blacklist từ file txt trong assets vào BloomFilter
        phishingFilter = loadTextToBloomFilter(context, FILE_PHISHING, 50000)
        adultFilter = loadTextToBloomFilter(context, FILE_ADULT, 20000)
        gamblingFilter = loadTextToBloomFilter(context, FILE_GAMBLING, 20000)

        Log.i(TAG, "Khởi tạo hoàn tất.")
    }

    // Hàm thêm domain vào whitelist tạm (5 phút)
    fun addTemporaryAllow(domain: String) {
        val cleanDomain = normalizeDomain(domain) ?: return
        // Thời gian hiện tại + 5 phút (300,000 ms)
        tempWhitelist[cleanDomain] = System.currentTimeMillis() + 300_000
        Log.i(TAG, "Đã bỏ chặn tạm thời: $cleanDomain")
    }

    // Hàm kiểm tra chính: Trả về TRUE nếu BỊ CHẶN
    fun isBlocked(domain: String): Boolean {
        val cleanDomain = normalizeDomain(domain) ?: return false

        // 1. Check Whitelist gốc
        if (whitelist.contains(cleanDomain)) return false

        // 2. Check Whitelist tạm thời (Do người dùng bấm "Vẫn truy cập")
        val expiryTime = tempWhitelist[cleanDomain]
        if (expiryTime != null) {
            if (System.currentTimeMillis() < expiryTime) {
                return false // Vẫn còn hạn -> Cho qua
            } else {
                tempWhitelist.remove(cleanDomain) // Hết hạn -> Xóa
            }
        }

        // 3. Check Phishing (Luôn chặn)
        if (phishingFilter?.mightContain(cleanDomain) == true) {
            Log.w(TAG, "BLOCKED [Phishing]: $cleanDomain")
            return true
        }

        // 4. Check Adult (Nếu bật)
        if (blockAdult && adultFilter?.mightContain(cleanDomain) == true) {
            Log.w(TAG, "BLOCKED [Adult]: $cleanDomain")
            return true
        }

        // 5. Check Gambling (Nếu bật)
        if (blockGambling && gamblingFilter?.mightContain(cleanDomain) == true) {
            Log.w(TAG, "BLOCKED [Gambling]: $cleanDomain")
            return true
        }

        return false
    }

    private fun loadWhitelist(context: Context) {
        whitelist.clear()
        try {
            val internalFile = File(context.filesDir, FILE_WHITELIST)
            val reader = if (internalFile.exists()) {
                internalFile.bufferedReader()
            } else {
                context.assets.open(FILE_WHITELIST).bufferedReader()
            }

            reader.useLines { lines ->
                lines.forEach { line ->
                    normalizeDomain(line)?.let { whitelist.add(it) }
                }
            }
            // Thêm cứng một số domain quan trọng
            whitelist.add("google.com")
            whitelist.add("android.com")
            whitelist.add("gstatic.com")
            whitelist.add("googleapis.com")

        } catch (e: Exception) {
            Log.e(TAG, "Lỗi load Whitelist: ${e.message}")
        }
    }

    private fun loadTextToBloomFilter(context: Context, fileName: String, expectedSize: Int): BloomFilter {
        val filter = BloomFilter.create(expectedInsertions = expectedSize, falsePositiveRate = 0.001)
        try {
            context.assets.open(fileName).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    normalizeDomain(line)?.let { filter.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Không tìm thấy hoặc lỗi file $fileName: ${e.message}")
        }
        return filter
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