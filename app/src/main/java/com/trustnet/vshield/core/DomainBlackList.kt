package com.trustnet.vshield.core

import android.content.Context
import android.util.Log
import com.trustnet.vshield.data.local.VShieldDatabase
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

object DomainBlacklist {

    private const val TAG = "DomainBlacklist"

    // Assets fallback
    private const val FILE_PHISHING  = "blacklist_phishing.txt"
    private const val FILE_ADULT     = "blacklist_adult.txt"
    private const val FILE_GAMBLING  = "blacklist_gambling.txt"
    private const val FILE_WHITELIST = "whitelist.txt"

    // File .bin cache trong filesDir
    private const val BIN_PHISHING  = "filter_phishing.bin"
    private const val BIN_ADULT     = "filter_adult.bin"
    private const val BIN_GAMBLING  = "filter_gambling.bin"
    private const val BIN_WHITELIST = "filter_whitelist.bin"
    // Không cần meta nữa vì thông số đã nằm trong từng file bin

    @Volatile var blockAdult: Boolean    = true
    @Volatile var blockGambling: Boolean = true

    private data class FilterSet(
        val phishing:  BloomFilter,
        val adult:     BloomFilter,
        val gambling:  BloomFilter,
        val whitelist: BloomFilter,
    )
    private val filterSet = AtomicReference<FilterSet?>()

    private val tempWhitelist = ConcurrentHashMap<String, Long>()

    @Volatile private var initialized = false
    @Volatile private var listsReady = false

    private val loadDeferred = CompletableDeferred<Unit>()

    enum class BlockCategory { PHISHING, ADULT, GAMBLING, WHITELIST, NONE }

    data class BlockMatch(
        val category: BlockCategory,
        val matchDomain: String,
    )

    private val common2LevelPublicSuffixPrefixes = setOf(
        "co", "com", "net", "org", "gov", "edu", "ac"
    )

    // ── PUBLIC API ────────────────────────────────────────────────────────────

    fun isListsReady(): Boolean = listsReady

    suspend fun awaitLoad(timeoutMs: Long = 10000): Boolean {
        return try {
            withTimeout(timeoutMs) { loadDeferred.await() }
            true
        } catch (e: TimeoutCancellationException) {
            false
        }
    }

    fun hasBinCache(context: Context): Boolean {
        return listOf(BIN_PHISHING, BIN_ADULT, BIN_GAMBLING, BIN_WHITELIST)
            .all { File(context.filesDir, it).exists() }
    }

    fun loadFromBinCache(context: Context): Boolean {
        return try {
            val pFilter = BloomFilter.fromBytes(File(context.filesDir, BIN_PHISHING).readBytes())
            val aFilter = BloomFilter.fromBytes(File(context.filesDir, BIN_ADULT).readBytes())
            val gFilter = BloomFilter.fromBytes(File(context.filesDir, BIN_GAMBLING).readBytes())
            val wFilter = BloomFilter.fromBytes(File(context.filesDir, BIN_WHITELIST).readBytes())

            filterSet.set(FilterSet(pFilter, aFilter, gFilter, wFilter))
            initialized = true
            listsReady = true
            loadDeferred.complete(Unit)

            Log.i(TAG, "Load .bin thành công: " +
                    "phishing=${pFilter.elementCount()}, adult=${aFilter.elementCount()}, " +
                    "gambling=${gFilter.elementCount()}, whitelist=${wFilter.elementCount()}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Load .bin thất bại: ${e.message}")
            false
        }
    }

    fun saveToBinCache(context: Context) {
        val fs = filterSet.get() ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                File(context.filesDir, BIN_PHISHING).writeBytes(fs.phishing.toBytes())
                File(context.filesDir, BIN_ADULT).writeBytes(fs.adult.toBytes())
                File(context.filesDir, BIN_GAMBLING).writeBytes(fs.gambling.toBytes())
                File(context.filesDir, BIN_WHITELIST).writeBytes(fs.whitelist.toBytes())
                Log.i(TAG, "Lưu .bin cache thành công")
            } catch (e: Exception) {
                Log.w(TAG, "Lưu .bin thất bại: ${e.message}")
            }
        }
    }

    fun clearBinCache(context: Context) {
        listOf(BIN_PHISHING, BIN_ADULT, BIN_GAMBLING, BIN_WHITELIST)
            .forEach { File(context.filesDir, it).delete() }
        Log.i(TAG, "Đã xóa .bin cache")
    }

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        CoroutineScope(Dispatchers.IO).launch {
            buildAndSwapFilters(context)
            listsReady = true
            loadDeferred.complete(Unit)
            Log.i(TAG, "DomainBlacklist init xong")
        }
    }

    fun reloadFromDatabase(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            buildAndSwapFilters(context)
            listsReady = true
            loadDeferred.complete(Unit)
            Log.i(TAG, "Reload BloomFilters xong")
        }
    }

    suspend fun reloadFromDatabaseSync(context: Context) {
        withContext(Dispatchers.IO) {
            buildAndSwapFilters(context)
        }
        listsReady = true
        loadDeferred.complete(Unit)
        Log.i(TAG, "Reload BloomFilters xong (sync)")
    }

    // ── PUBLIC API — Matching ─────────────────────────────────────────────────

    fun getBlockCategory(domain: String): BlockCategory =
        match(domain)?.category ?: BlockCategory.NONE

    fun match(domain: String): BlockMatch? {
        val clean = normalizeDomain(domain) ?: return null
        val candidates = domainCandidates(clean)

        val now = System.currentTimeMillis()
        for (c in candidates) {
            val expiry = tempWhitelist[c]
            if (expiry != null) {
                if (now < expiry) return BlockMatch(BlockCategory.WHITELIST, c)
                tempWhitelist.remove(c)
            }
        }

        val fs = filterSet.get()
        if (fs == null) {
            // Nếu filter chưa sẵn sàng, trả về null (caller coi như NONE)
            Log.w(TAG, "match: filterSet is NULL for domain=$clean")
            return null
        }

        // 1. Whitelist
        for (c in candidates) {
            if (fs.whitelist.mightContain(c)) {
                Log.d(TAG, "ALLOW by whitelist: $c (query=$clean)")
                return BlockMatch(BlockCategory.WHITELIST, c)
            }
        }

        // 2. Phishing
        for (c in candidates) {
            if (fs.phishing.mightContain(c)) {
                Log.w(TAG, "BLOCKED [Phishing]: $c (query=$clean)")
                return BlockMatch(BlockCategory.PHISHING, c)
            }
        }

        // 3. Adult
        if (blockAdult) {
            for (c in candidates) {
                if (fs.adult.mightContain(c)) {
                    Log.w(TAG, "BLOCKED [Adult]: $c (query=$clean)")
                    return BlockMatch(BlockCategory.ADULT, c)
                }
            }
        }

        // 4. Gambling
        if (blockGambling) {
            for (c in candidates) {
                if (fs.gambling.mightContain(c)) {
                    Log.w(TAG, "BLOCKED [Gambling]: $c (query=$clean)")
                    return BlockMatch(BlockCategory.GAMBLING, c)
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

    // ── PRIVATE — Core load logic ─────────────────────────────────────────────

    private suspend fun buildAndSwapFilters(context: Context) {
        val newFilterSet = FilterSet(
            phishing  = buildBlacklistFilter(context, "phishing", FILE_PHISHING, 100_000),
            adult     = buildBlacklistFilter(context, "adult",    FILE_ADULT,    50_000),
            gambling  = buildBlacklistFilter(context, "gambling", FILE_GAMBLING, 50_000),
            whitelist = buildWhitelistFilter(context),
        )
        filterSet.set(newFilterSet)
    }

    private suspend fun buildBlacklistFilter(
        context: Context,
        category: String,
        assetFile: String,
        defaultSize: Int,
    ): BloomFilter {
        return try {
            val db  = VShieldDatabase.getInstance(context)
            val dao = db.blocklistDao()
            val domains = dao.getActiveDomainsByCategory(category)
            if (domains.isEmpty()) throw Exception("DB trống cho category=$category")
            Log.i(TAG, "Blacklist $category từ DB: ${domains.size} domains")
            buildFilter(domains, maxOf(defaultSize, domains.size))
        } catch (e: Exception) {
            Log.w(TAG, "Load DB $category fail, fallback assets: ${e.message}")
            loadFile(context, assetFile, defaultSize)
        }
    }

    private suspend fun buildWhitelistFilter(context: Context): BloomFilter {
        val db           = VShieldDatabase.getInstance(context)
        val whitelistDao = db.whitelistDao()

        return try {
            val dbWhitelist     = whitelistDao.getAllDomains().mapNotNull { normalizeDomain(it) }
            val staticWhitelist = loadStaticWhitelistList(context)

            val merged = LinkedHashSet<String>(staticWhitelist.size + dbWhitelist.size)
            merged.addAll(staticWhitelist)
            merged.addAll(dbWhitelist)

            Log.i(TAG, "Whitelist: total=${merged.size}")
            buildFilter(merged.toList(), maxOf(merged.size, 250_000))
        } catch (e: Exception) {
            Log.w(TAG, "Load whitelist DB fail, fallback assets only: ${e.message}")
            val staticWhitelist = loadStaticWhitelistList(context)
            buildFilter(staticWhitelist, maxOf(staticWhitelist.size, 50_000))
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

    // ── PRIVATE — Domain helpers (giữ nguyên) ─────────────────────────────────

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
        if (s.contains(git "://")) s = s.substringAfter("://")
        if (s.contains("/"))   s = s.substringBefore("/")
        if (s.contains(":"))   s = s.substringBefore(":")
        s = s.removePrefix("*.").removePrefix("www.").trimEnd('.')
        if (s.length < 3 || !s.contains(".")) return null
        return s
    }
}