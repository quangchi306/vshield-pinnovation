package com.trustnet.vshield.core

import android.content.Context
import android.util.Log
import com.trustnet.vshield.data.local.VShieldDatabase
import com.trustnet.vshield.core.BloomFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

object DomainBlacklist {

    private const val TAG = "DomainBlacklist"

    private const val FILE_PHISHING  = "blacklist_phishing.txt"
    private const val FILE_ADULT     = "blacklist_adult.txt"
    private const val FILE_GAMBLING  = "blacklist_gambling.txt"
    private const val FILE_WHITELIST = "whitelist.txt"

    @Volatile var blockAdult = true
    @Volatile var blockGambling = true

    // Không dùng HashSet whitelist nữa
    private val whitelistFilter = AtomicReference<BloomFilter?>()

    private val tempWhitelist = ConcurrentHashMap<String, Long>()

    private val phishingFilter = AtomicReference<BloomFilter?>()
    private val adultFilter = AtomicReference<BloomFilter?>()
    private val gamblingFilter = AtomicReference<BloomFilter?>()

    @Volatile private var initialized = false

    enum class BlockCategory { PHISHING, ADULT, GAMBLING, NONE }

    data class BlockMatch(
        val category: BlockCategory,
        val matchDomain: String,
    )

    private val common2LevelPublicSuffixPrefixes = setOf(
        "co", "com", "net", "org", "gov", "edu", "ac"
    )

    fun init(context: Context) {
        if (initialized) return
        initialized = true

        CoroutineScope(Dispatchers.IO).launch {
            loadBlocklistFromDatabase(context)
            loadWhitelistFromDatabase(context)
            Log.i(TAG, "DomainBlacklist init xong")
        }
    }

    fun reloadFromDatabase(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            loadBlocklistFromDatabase(context)
            loadWhitelistFromDatabase(context)
            Log.i(TAG, "Reload BloomFilters xong")
        }
    }

    fun getBlockCategory(domain: String): BlockCategory =
        match(domain)?.category ?: BlockCategory.NONE

    fun match(domain: String): BlockMatch? {
        val clean = normalizeDomain(domain) ?: return null
        val candidates = domainCandidates(clean)

        val now = System.currentTimeMillis()
        for (c in candidates) {
            val expiry = tempWhitelist[c]
            if (expiry != null) {
                if (now < expiry) return null
                tempWhitelist.remove(c)
            }
        }

        //Whitelist BloomFilter
        val wf = whitelistFilter.get()
        if (wf != null) {
            for (c in candidates) {
                if (wf.mightContain(c)) {
                    Log.d(TAG, "ALLOW by whitelist bloom: $c (query=$clean)")
                    return null
                }
            }
        }

        //Phishing
        val pf = phishingFilter.get()
        if (pf != null) {
            for (c in candidates) {
                if (pf.mightContain(c)) {
                    Log.w(TAG, "BLOCKED [Phishing]: $c (query=$clean)")
                    return BlockMatch(BlockCategory.PHISHING, c)
                }
            }
        }

        //Adult
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

        //Gambling
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

    fun isBlocked(domain: String): Boolean =
        getBlockCategory(domain) != BlockCategory.NONE

    fun addTemporaryAllow(domain: String) {
        val clean = normalizeDomain(domain) ?: return
        val candidates = domainCandidates(clean)
        val expiry = System.currentTimeMillis() + 300_000L
        candidates.forEach { tempWhitelist[it] = expiry }
        Log.i(TAG, "Bypass tạm 5 phút: $clean")
    }

    private suspend fun loadBlocklistFromDatabase(context: Context) {
        val db = VShieldDatabase.getInstance(context)
        val dao = db.blocklistDao()

        try {
            val phishingDomains = dao.getActiveDomainsByCategory("phishing")
            val adultDomains = dao.getActiveDomainsByCategory("adult")
            val gamblingDomains = dao.getActiveDomainsByCategory("gambling")

            phishingFilter.set(buildFilter(phishingDomains, maxOf(100_000, phishingDomains.size)))
            adultFilter.set(buildFilter(adultDomains, maxOf(50_000, adultDomains.size)))
            gamblingFilter.set(buildFilter(gamblingDomains, maxOf(50_000, gamblingDomains.size)))

            Log.i(
                TAG,
                "Loaded blocklist from DB: phishing=${phishingDomains.size}, adult=${adultDomains.size}, gambling=${gamblingDomains.size}"
            )
            return
        } catch (e: Exception) {
            Log.w(TAG, "Load DB blocklist fail, fallback assets: ${e.message}")
        }

        phishingFilter.set(loadFile(context, FILE_PHISHING, 100_000))
        adultFilter.set(loadFile(context, FILE_ADULT, 50_000))
        gamblingFilter.set(loadFile(context, FILE_GAMBLING, 50_000))
    }

    private suspend fun loadWhitelistFromDatabase(context: Context) {
        val db = VShieldDatabase.getInstance(context)
        val whitelistDao = db.whitelistDao()

        try {
            val dbWhitelist = whitelistDao.getAllDomains()
                .mapNotNull { normalizeDomain(it) }

            val staticWhitelist = loadStaticWhitelistList(context)

            val merged = LinkedHashSet<String>(staticWhitelist.size + dbWhitelist.size)
            merged.addAll(staticWhitelist)
            merged.addAll(dbWhitelist)

            val filter = buildFilter(merged.toList(), maxOf(merged.size, 250_000))
            whitelistFilter.set(filter)

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
                lines.forEach { line ->
                    normalizeDomain(line)?.let { out.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không tìm thấy assets/$FILE_WHITELIST: ${e.message}")
        }

        out.addAll(
            listOf(
                "google.com",
                "android.com",
                "gstatic.com",
                "googleapis.com",
                "play.google.com",
                "accounts.google.com"
            )
        )

        return out.distinct()
    }

    private fun loadFile(context: Context, fileName: String, size: Int): BloomFilter {
        val filter = BloomFilter.create(
            expectedInsertions = size,
            falsePositiveRate = 0.001
        )

        try {
            context.assets.open(fileName).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    normalizeDomain(line)?.let { filter.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Không tìm thấy assets/$fileName: ${e.message}")
        }

        return filter
    }

    private fun buildFilter(domains: List<String>, size: Int): BloomFilter {
        val filter = BloomFilter.create(
            expectedInsertions = size,
            falsePositiveRate = 0.001
        )

        domains.forEach { d ->
            normalizeDomain(d)?.let { filter.add(it) }
        }

        return filter
    }

    private fun domainCandidates(cleanDomain: String): List<String> {
        val parts = cleanDomain.split('.')
        if (parts.size <= 2) return listOf(cleanDomain)

        val out = ArrayList<String>(parts.size)
        for (i in 0..(parts.size - 2)) {
            val candidateParts = parts.subList(i, parts.size)
            if (candidateParts.size < 2) continue

            if (candidateParts.size == 2) {
                val left = candidateParts[0]
                val right = candidateParts[1]
                if (right.length == 2 && left in common2LevelPublicSuffixPrefixes) {
                    continue
                }
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
        if (s.contains("/")) s = s.substringBefore("/")
        if (s.contains(":")) s = s.substringBefore(":")
        s = s.removePrefix("*.").removePrefix("www.").trimEnd('.')

        if (s.length < 3 || !s.contains(".")) return null
        return s
    }
}