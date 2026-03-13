package com.trustnet.vshield.core

import android.content.Context
import android.util.Log
import com.trustnet.vshield.data.local.VShieldDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

object DomainBlacklist {

    private const val TAG = "DomainBlacklist"

    //Assets fallback
    private const val FILE_PHISHING  = "blacklist_phishing.txt"
    private const val FILE_ADULT     = "blacklist_adult.txt"
    private const val FILE_GAMBLING  = "blacklist_gambling.txt"
    private const val FILE_WHITELIST = "whitelist.txt"

    //File .bin cache trong filesDir
    private const val BIN_PHISHING  = "filter_phishing.bin"
    private const val BIN_ADULT     = "filter_adult.bin"
    private const val BIN_GAMBLING  = "filter_gambling.bin"
    private const val BIN_WHITELIST = "filter_whitelist.bin"
    private const val BIN_META      = "filter_meta.json"

    @Volatile var blockAdult: Boolean    = true
    @Volatile var blockGambling: Boolean = true

    private val whitelistFilter = AtomicReference<BloomFilter?>()
    private val tempWhitelist   = ConcurrentHashMap<String, Long>()
    private val phishingFilter  = AtomicReference<BloomFilter?>()
    private val adultFilter     = AtomicReference<BloomFilter?>()
    private val gamblingFilter  = AtomicReference<BloomFilter?>()

    @Volatile private var initialized = false

    enum class BlockCategory { PHISHING, ADULT, GAMBLING, WHITELIST, NONE }

    data class BlockMatch(
        val category: BlockCategory,
        val matchDomain: String,
    )

    private val common2LevelPublicSuffixPrefixes = setOf(
        "co", "com", "net", "org", "gov", "edu", "ac"
    )

    //BinMeta — lưu số domain count để load .bin đúng tham số
    private data class BinMeta(
        val phishingCount:  Int = 100_000,
        val adultCount:     Int = 50_000,
        val gamblingCount:  Int = 50_000,
        val whitelistCount: Int = 250_000,
    )

    // PUBLIC API — .bin cache

    /**
     * Kiểm tra đủ 5 file cache chưa (4 .bin + 1 meta).
     */
    fun hasBinCache(context: Context): Boolean {
        return listOf(BIN_PHISHING, BIN_ADULT, BIN_GAMBLING, BIN_WHITELIST, BIN_META)
            .all { File(context.filesDir, it).exists() }
    }

    /**
     * Load BloomFilter từ .bin — chỉ mất vài chục ms.
     * Trả về true nếu thành công, false nếu lỗi (cần fallback DB).
     */
    fun loadFromBinCache(context: Context): Boolean {
        return try {
            val meta = readMeta(context)

            phishingFilter.set(
                BloomFilter.loadFromByteArray(
                    File(context.filesDir, BIN_PHISHING).readBytes(),
                    meta.phishingCount
                )
            )
            adultFilter.set(
                BloomFilter.loadFromByteArray(
                    File(context.filesDir, BIN_ADULT).readBytes(),
                    meta.adultCount
                )
            )
            gamblingFilter.set(
                BloomFilter.loadFromByteArray(
                    File(context.filesDir, BIN_GAMBLING).readBytes(),
                    meta.gamblingCount
                )
            )
            whitelistFilter.set(
                BloomFilter.loadFromByteArray(
                    File(context.filesDir, BIN_WHITELIST).readBytes(),
                    meta.whitelistCount
                )
            )

            initialized = true
            Log.i(TAG, "Load .bin thành công: " +
                    "phishing=${meta.phishingCount}, " +
                    "adult=${meta.adultCount}, " +
                    "gambling=${meta.gamblingCount}, " +
                    "whitelist=${meta.whitelistCount}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Load .bin thất bại, sẽ fallback DB: ${e.message}")
            false
        }
    }

    /**
     * Lưu BloomFilter hiện tại xuống .bin — chạy ngầm trên IO.
     * Gọi sau khi reloadFromDatabase() hoàn tất.
     */
    fun saveToBinCache(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val p = phishingFilter.get()  ?: return@launch
                val a = adultFilter.get()     ?: return@launch
                val g = gamblingFilter.get()  ?: return@launch
                val w = whitelistFilter.get() ?: return@launch

                File(context.filesDir, BIN_PHISHING).writeBytes(p.toByteArray())
                File(context.filesDir, BIN_ADULT).writeBytes(a.toByteArray())
                File(context.filesDir, BIN_GAMBLING).writeBytes(g.toByteArray())
                File(context.filesDir, BIN_WHITELIST).writeBytes(w.toByteArray())

                saveMeta(context, BinMeta(
                    phishingCount  = p.elementCount(),
                    adultCount     = a.elementCount(),
                    gamblingCount  = g.elementCount(),
                    whitelistCount = w.elementCount(),
                ))

                Log.i(TAG, "Lưu .bin cache thành công")
            } catch (e: Exception) {
                Log.w(TAG, "Lưu .bin thất bại: ${e.message}")
            }
        }
    }

    /**
     * Xóa toàn bộ .bin cache — gọi khi cần force full sync lại.
     */
    fun clearBinCache(context: Context) {
        listOf(BIN_PHISHING, BIN_ADULT, BIN_GAMBLING, BIN_WHITELIST, BIN_META)
            .forEach { File(context.filesDir, it).delete() }
        Log.i(TAG, "Đã xóa .bin cache")
    }

    // PUBLIC API — Init / Reload

    /**
     * Khởi tạo lần đầu — chỉ dùng khi KHÔNG có .bin cache.
     * Đọc từ Room DB, fallback về Assets nếu DB trống.
     */
    fun init(context: Context) {
        if (initialized) return
        initialized = true

        CoroutineScope(Dispatchers.IO).launch {
            loadBlocklistFromDatabase(context)
            loadWhitelistFromDatabase(context)
            Log.i(TAG, "DomainBlacklist init xong")
        }
    }

    /**
     * Reload từ Room DB sau khi sync server xong.
     * Sau khi xong nhớ gọi saveToBinCache() để cập nhật cache.
     */
    fun reloadFromDatabase(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            loadBlocklistFromDatabase(context)
            loadWhitelistFromDatabase(context)
            Log.i(TAG, "Reload BloomFilters xong")
        }
    }

    suspend fun reloadFromDatabaseSync(context: Context) {
        withContext(Dispatchers.IO) {
            loadBlocklistFromDatabase(context)
            loadWhitelistFromDatabase(context)
        }
        Log.i(TAG, "Reload BloomFilters xong (sync)")
    }

    // PUBLIC API — Matching
    fun getBlockCategory(domain: String): BlockCategory =
        match(domain)?.category ?: BlockCategory.NONE

    fun match(domain: String): BlockMatch? {
        val clean      = normalizeDomain(domain) ?: return null
        val candidates = domainCandidates(clean)

        // 1. Temp whitelist
        val now = System.currentTimeMillis()
        for (c in candidates) {
            val expiry = tempWhitelist[c]
            if (expiry != null) {
                if (now < expiry) return BlockMatch(BlockCategory.WHITELIST, c)
                tempWhitelist.remove(c)
            }
        }

        // 2. Whitelist BloomFilter
        val wf = whitelistFilter.get()
        if (wf != null) {
            for (c in candidates) {
                if (wf.mightContain(c)) {
                    Log.d(TAG, "ALLOW by whitelist bloom: $c (query=$clean)")
                    return BlockMatch(BlockCategory.WHITELIST, c)
                }
            }
        }

        // 3. Phishing
        val pf = phishingFilter.get()
        if (pf != null) {
            for (c in candidates) {
                if (pf.mightContain(c)) {
                    Log.w(TAG, "BLOCKED [Phishing]: $c (query=$clean)")
                    return BlockMatch(BlockCategory.PHISHING, c)
                }
            }
        }

        // 4. Adult
        if (blockAdult) {
            val af = adultFilter.get()
            if (af != null) {
                for (c in candidates) {
                    if (af.mightContain(c)) {
                        Log.w(TAG, "BLOCKED [Adult]: $c (query=$clean)")
                        return BlockMatch(BlockCategory.ADULT, c)
                    }
                }
            }
        }

        // 5. Gambling
        if (blockGambling) {
            val gf = gamblingFilter.get()
            if (gf != null) {
                for (c in candidates) {
                    if (gf.mightContain(c)) {
                        Log.w(TAG, "BLOCKED [Gambling]: $c (query=$clean)")
                        return BlockMatch(BlockCategory.GAMBLING, c)
                    }
                }
            }
        }

        return null
    }

    fun isBlocked(domain: String): Boolean {
        val cat = getBlockCategory(domain)
        return cat == BlockCategory.PHISHING ||
                cat == BlockCategory.ADULT   ||
                cat == BlockCategory.GAMBLING
    }

    fun addTemporaryAllow(domain: String) {
        val clean = normalizeDomain(domain) ?: return
        val candidates = domainCandidates(clean)
        val expiry = System.currentTimeMillis() + 300_000L
        candidates.forEach { tempWhitelist[it] = expiry }
        Log.i(TAG, "Bypass tạm 5 phút: $clean")
    }

    // PRIVATE — Load từ DB / Assets
    private suspend fun loadBlocklistFromDatabase(context: Context) {
        val db  = VShieldDatabase.getInstance(context)
        val dao = db.blocklistDao()

        try {
            val phishingDomains = dao.getActiveDomainsByCategory("phishing")
            val adultDomains    = dao.getActiveDomainsByCategory("adult")
            val gamblingDomains = dao.getActiveDomainsByCategory("gambling")

            if (phishingDomains.isEmpty() && adultDomains.isEmpty() && gamblingDomains.isEmpty()) {
                throw Exception("Database trống, chuyển sang dùng Assets")
            }

            phishingFilter.set(buildFilter(phishingDomains, maxOf(100_000, phishingDomains.size)))
            adultFilter.set(buildFilter(adultDomains,       maxOf(50_000,  adultDomains.size)))
            gamblingFilter.set(buildFilter(gamblingDomains, maxOf(50_000,  gamblingDomains.size)))

            Log.i(TAG, "Loaded blocklist from DB: " +
                    "phishing=${phishingDomains.size}, " +
                    "adult=${adultDomains.size}, " +
                    "gambling=${gamblingDomains.size}")
            return
        } catch (e: Exception) {
            Log.w(TAG, "Load DB blocklist fail, fallback assets: ${e.message}")
        }

        // Fallback đọc từ Assets
        phishingFilter.set(loadFile(context, FILE_PHISHING, 100_000))
        adultFilter.set(loadFile(context,    FILE_ADULT,    50_000))
        gamblingFilter.set(loadFile(context, FILE_GAMBLING, 50_000))
    }

    private suspend fun loadWhitelistFromDatabase(context: Context) {
        val db           = VShieldDatabase.getInstance(context)
        val whitelistDao = db.whitelistDao()

        try {
            val dbWhitelist     = whitelistDao.getAllDomains().mapNotNull { normalizeDomain(it) }
            val staticWhitelist = loadStaticWhitelistList(context)

            val merged = LinkedHashSet<String>(staticWhitelist.size + dbWhitelist.size)
            merged.addAll(staticWhitelist)
            merged.addAll(dbWhitelist)

            whitelistFilter.set(buildFilter(merged.toList(), maxOf(merged.size, 250_000)))
            Log.i(TAG, "Whitelist bloom loaded: total=${merged.size}")
        } catch (e: Exception) {
            Log.w(TAG, "Load whitelist DB fail, fallback assets only: ${e.message}")
            val staticWhitelist = loadStaticWhitelistList(context)
            whitelistFilter.set(buildFilter(staticWhitelist, maxOf(staticWhitelist.size, 50_000)))
        }
    }

    private fun loadStaticWhitelistList(context: Context): List<String> {
        val out = ArrayList<String>()
        try {
            context.assets.open(FILE_WHITELIST).bufferedReader().useLines { lines ->
                lines.forEach { line -> normalizeDomain(line)?.let { out.add(it) } }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không tìm thấy assets/$FILE_WHITELIST: ${e.message}")
        }
        out.addAll(listOf(
            "google.com", "android.com", "gstatic.com",
            "googleapis.com", "play.google.com", "accounts.google.com"
        ))
        return out.distinct()
    }

    private fun loadFile(context: Context, fileName: String, size: Int): BloomFilter {
        val filter = BloomFilter.create(expectedInsertions = size, falsePositiveRate = 0.0000001)
        try {
            context.assets.open(fileName).bufferedReader().useLines { lines ->
                lines.forEach { line -> normalizeDomain(line)?.let { filter.add(it) } }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không tìm thấy assets/$fileName: ${e.message}")
        }
        return filter
    }

    private fun buildFilter(domains: List<String>, size: Int): BloomFilter {
        val filter = BloomFilter.create(expectedInsertions = size, falsePositiveRate = 0.0000001)
        domains.forEach { d -> normalizeDomain(d)?.let { filter.add(it) } }
        return filter
    }

    // PRIVATE — Metadata .bin
    private fun saveMeta(context: Context, meta: BinMeta) {
        val json = JSONObject().apply {
            put("phishingCount",  meta.phishingCount)
            put("adultCount",     meta.adultCount)
            put("gamblingCount",  meta.gamblingCount)
            put("whitelistCount", meta.whitelistCount)
        }
        File(context.filesDir, BIN_META).writeText(json.toString())
    }

    private fun readMeta(context: Context): BinMeta {
        return try {
            val json = JSONObject(File(context.filesDir, BIN_META).readText())
            BinMeta(
                phishingCount  = json.optInt("phishingCount",  100_000),
                adultCount     = json.optInt("adultCount",     50_000),
                gamblingCount  = json.optInt("gamblingCount",  50_000),
                whitelistCount = json.optInt("whitelistCount", 250_000),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Đọc meta thất bại, dùng default: ${e.message}")
            BinMeta()
        }
    }

    // PRIVATE — Domain helpers
    private fun domainCandidates(cleanDomain: String): List<String> {
        val parts = cleanDomain.split('.')
        if (parts.size <= 2) return listOf(cleanDomain)

        val out = ArrayList<String>(parts.size)
        for (i in 0..(parts.size - 2)) {
            val candidateParts = parts.subList(i, parts.size)
            if (candidateParts.size < 2) continue
            if (candidateParts.size == 2) {
                val left  = candidateParts[0]
                val right = candidateParts[1]
                if (right.length == 2 && left in common2LevelPublicSuffixPrefixes) continue
            }
            out.add(candidateParts.joinToString("."))
        }
        if (out.isEmpty()) out.add(cleanDomain)
        return out
    }

    private fun normalizeDomain(raw: String): String? {
        if (raw.trim().startsWith("#") || raw.isBlank()) return null
        var s = raw.trim().lowercase(Locale.ROOT)
        if (s.contains("://")) s = s.substringAfter("://")
        if (s.contains("/"))   s = s.substringBefore("/")
        if (s.contains(":"))   s = s.substringBefore(":")
        s = s.removePrefix("*.").removePrefix("www.").trimEnd('.')
        if (s.length < 3 || !s.contains(".")) return null
        return s
    }
}