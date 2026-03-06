package com.trustnet.vshield.core

import android.content.Context
import android.util.Log
import com.trustnet.vshield.data.local.VShieldDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.HashSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

object DomainBlacklist {
    private const val TAG = "DomainBlacklist"

    private const val FILE_WHITELIST = "whitelist.txt"
    private const val FILE_PHISHING  = "blacklist_phishing.txt"
    private const val FILE_ADULT     = "blacklist_adult.txt"
    private const val FILE_GAMBLING  = "blacklist_gambling.txt"

    @Volatile var blockAdult:    Boolean = true
    @Volatile var blockGambling: Boolean = true

    // Whitelist: HashSet
    // Không có trong adult/gambling list
    private val whitelist     = HashSet<String>()
    private val tempWhitelist = ConcurrentHashMap<String, Long>()

    // Blocklist: BloomFilter
    private val phishingFilter = AtomicReference<BloomFilter?>(null)
    private val adultFilter    = AtomicReference<BloomFilter?>(null)
    private val gamblingFilter = AtomicReference<BloomFilter?>(null)

    @Volatile private var initialized = false

    enum class BlockCategory { PHISHING, ADULT, GAMBLING, NONE }

    //Init
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        Log.i(TAG, "Đang khởi tạo bộ lọc...")

        // Load whitelist tĩnh trước (assets + hardcode) — nhanh, đồng bộ
        loadStaticWhitelist(context)

        CoroutineScope(Dispatchers.IO).launch {
            val dao          = VShieldDatabase.getInstance(context).blocklistDao()
            val whitelistDao = VShieldDatabase.getInstance(context).whitelistDao()

            if (dao.countActive() > 0) {
                Log.i(TAG, "Load BloomFilter từ Room DB...")
                loadBlocklistFromDatabase(context)
            } else {
                Log.i(TAG, "Room trống, load từ assets (fallback)...")
                loadFromAssets(context)
            }

            if (whitelistDao.count() > 0) {
                // Load whitelist sau khi BloomFilter đã sẵn sàng
                // để loại domain adult/gambling ra khỏi whitelist
                loadWhitelistFromDatabase(context)
            }

            Log.i(TAG, "Khởi tạo hoàn tất. Whitelist: ${whitelist.size}")
        }
    }

    fun reloadFromDatabase(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            loadBlocklistFromDatabase(context)   // 1. Load BloomFilter trước
            loadWhitelistFromDatabase(context)   // 2. Load whitelist sau (cần BloomFilter để lọc)
            Log.i(TAG, "Reload xong. Whitelist: ${whitelist.size}")
        }
    }

    // ── Hàm kiểm tra chính ──────────────────────────────────────────────────

    fun getBlockCategory(domain: String): BlockCategory {
        val clean = normalizeDomain(domain) ?: return BlockCategory.NONE

        if (whitelist.contains(clean)) {
            return BlockCategory.NONE
        }

        // 2. Temp whitelist
        val expiry = tempWhitelist[clean]
        if (expiry != null) {
            if (System.currentTimeMillis() < expiry) return BlockCategory.NONE
            else tempWhitelist.remove(clean)
        }

        // 3. Phishing
        if (phishingFilter.get()?.mightContain(clean) == true) {
            Log.w(TAG, "BLOCKED [Phishing]: $clean")
            return BlockCategory.PHISHING
        }

        // 4. Adult (hiện màn hình tùy chọn nếu bật)
        if (blockAdult && adultFilter.get()?.mightContain(clean) == true) {
            Log.w(TAG, "BLOCKED [Adult]: $clean")
            return BlockCategory.ADULT
        }

        // 5. Gambling (hiện màn hình tùy chọn nếu bật)
        if (blockGambling && gamblingFilter.get()?.mightContain(clean) == true) {
            Log.w(TAG, "BLOCKED [Gambling]: $clean")
            return BlockCategory.GAMBLING
        }

        return BlockCategory.NONE
    }

    fun isBlocked(domain: String): Boolean =
        getBlockCategory(domain) != BlockCategory.NONE

    fun addTemporaryAllow(domain: String) {
        val clean = normalizeDomain(domain) ?: return
        tempWhitelist[clean] = System.currentTimeMillis() + 300_000
        Log.i(TAG, "Bypass tạm thời 5 phút: $clean")
    }

    //Private: Load BloomFilter từ Room

    private suspend fun loadBlocklistFromDatabase(context: Context) {
        val dao = VShieldDatabase.getInstance(context).blocklistDao()

        val newPhishing = buildFilter(dao.getActiveDomainsByCategory("phishing"), 100_000)
        val newAdult    = buildFilter(dao.getActiveDomainsByCategory("adult"),     50_000)
        val newGambling = buildFilter(dao.getActiveDomainsByCategory("gambling"),  50_000)

        phishingFilter.set(newPhishing)
        adultFilter.set(newAdult)
        gamblingFilter.set(newGambling)

        Log.i(TAG, "BloomFilter loaded từ DB")
    }

    private suspend fun loadWhitelistFromDatabase(context: Context) {
        val db           = VShieldDatabase.getInstance(context)
        val whitelistDao = db.whitelistDao()
        val blocklistDao = db.blocklistDao()

        // Lấy toàn bộ domain adult + gambling từ DB làm tập loại trừ
        val adultSet    = blocklistDao.getActiveDomainsByCategory("adult").toHashSet()
        val gamblingSet = blocklistDao.getActiveDomainsByCategory("gambling").toHashSet()
        val blockedContentSet = adultSet + gamblingSet

        val allWhitelist = whitelistDao.getAllDomains()
        var added   = 0
        var skipped = 0

        synchronized(whitelist) {
            // Reset về whitelist tĩnh, xây lại từ đầu
            loadStaticWhitelist_sync()

            allWhitelist.forEach { domain ->
                val clean = domain.lowercase(Locale.ROOT)
                if (clean in blockedContentSet) {
                    skipped++ // Có trong adult/gambling, không vào whitelist
                } else {
                    whitelist.add(clean)
                    added++
                }
            }
        }

        Log.i(TAG, "Whitelist: +$added domain Tranco, bỏ qua $skipped (có trong adult/gambling), tổng: ${whitelist.size}")
    }

    // ── Private: Load assets (fallback) ─────────────────────────────────────

    private fun loadFromAssets(context: Context) {
        phishingFilter.set(loadFile(context, FILE_PHISHING, 50_000))
        adultFilter.set(loadFile(context, FILE_ADULT,       20_000))
        gamblingFilter.set(loadFile(context, FILE_GAMBLING, 20_000))
    }

    private fun loadStaticWhitelist(context: Context) {
        whitelist.clear()
        try {
            context.assets.open(FILE_WHITELIST).bufferedReader().useLines { lines ->
                lines.forEach { normalizeDomain(it)?.let { d -> whitelist.add(d) } }
            }
        } catch (_: Exception) {}
        addHardcodedWhitelist()
        Log.i(TAG, "Static whitelist: ${whitelist.size} domain")
    }

    private fun loadStaticWhitelist_sync() {
        whitelist.clear()
        addHardcodedWhitelist()
    }

    private fun addHardcodedWhitelist() {
        whitelist.addAll(listOf(
            "google.com", "android.com", "gstatic.com", "googleapis.com",
            "play.google.com", "accounts.google.com",
        ))
    }

    //Private: Helpers

    private fun loadFile(context: Context, fileName: String, size: Int): BloomFilter {
        val filter = BloomFilter.create(expectedInsertions = size, falsePositiveRate = 0.001)
        try {
            context.assets.open(fileName).bufferedReader().useLines { lines ->
                lines.forEach { normalizeDomain(it)?.let { d -> filter.add(d) } }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không tìm thấy assets/$fileName: ${e.message}")
        }
        return filter
    }

    private fun buildFilter(domains: List<String>, size: Int): BloomFilter {
        val filter = BloomFilter.create(expectedInsertions = size, falsePositiveRate = 0.001)
        domains.forEach { filter.add(it) }
        return filter
    }

    private fun normalizeDomain(raw: String): String? {
        if (raw.trim().startsWith("#") || raw.isBlank()) return null
        var s = raw.trim().lowercase(Locale.ROOT)
        if (s.contains("://")) s = s.substringAfter("://")
        if (s.contains("/"))   s = s.substringBefore("/")
        s = s.removePrefix("www.").trimEnd('.')
        if (s.length < 3 || !s.contains(".")) return null
        return s
    }
}