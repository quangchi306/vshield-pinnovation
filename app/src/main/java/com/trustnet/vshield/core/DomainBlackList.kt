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

    private val whitelist    = HashSet<String>()
    private val tempWhitelist = ConcurrentHashMap<String, Long>()

    // AtomicReference để swap BloomFilter an toàn khi rebuild
    private val phishingFilter = AtomicReference<BloomFilter?>(null)
    private val adultFilter    = AtomicReference<BloomFilter?>(null)
    private val gamblingFilter = AtomicReference<BloomFilter?>(null)

    @Volatile private var initialized = false

    /**
     * Gọi trong VShieldVpnService.onCreate() — giữ nguyên như cũ.
     * Tự động load từ Room nếu có data, fallback về assets nếu chưa sync.
     */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        loadWhitelist(context)

        CoroutineScope(Dispatchers.IO).launch {
            val dao   = VShieldDatabase.getInstance(context).blocklistDao()
            val total = dao.countActive()
            if (total > 0) {
                Log.i(TAG, "Load từ Room DB ($total domains)...")
                loadFromDatabase(context)
            } else {
                Log.i(TAG, "Room trống, load từ assets (fallback)...")
                loadFromAssets(context)
            }
        }
    }

    /**
     * Gọi sau khi sync xong để BloomFilter dùng data mới nhất.
     * Thread-safe nhờ AtomicReference.
     */
    fun reloadFromDatabase(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            loadFromDatabase(context)
            Log.i(TAG, "BloomFilter reload từ DB xong.")
        }
    }

    // ─── isBlocked — KHÔNG thay đổi gì, VpnWorker vẫn gọi bình thường ────────

    fun isBlocked(domain: String): Boolean {
        val clean = normalizeDomain(domain) ?: return false

        if (whitelist.contains(clean)) return false

        val expiry = tempWhitelist[clean]
        if (expiry != null) {
            if (System.currentTimeMillis() < expiry) return false
            else tempWhitelist.remove(clean)
        }

        if (phishingFilter.get()?.mightContain(clean) == true) {
            Log.w(TAG, "BLOCKED [Phishing]: $clean")
            return true
        }
        if (blockAdult && adultFilter.get()?.mightContain(clean) == true) {
            Log.w(TAG, "BLOCKED [Adult]: $clean")
            return true
        }
        if (blockGambling && gamblingFilter.get()?.mightContain(clean) == true) {
            Log.w(TAG, "BLOCKED [Gambling]: $clean")
            return true
        }
        return false
    }

    fun addTemporaryAllow(domain: String) {
        val clean = normalizeDomain(domain) ?: return
        tempWhitelist[clean] = System.currentTimeMillis() + 300_000
        Log.i(TAG, "Bypass tạm thời: $clean")
    }

    // ─── Private ──────────────────────────────────────────────────────────────

    private suspend fun loadFromDatabase(context: Context) {
        val dao = VShieldDatabase.getInstance(context).blocklistDao()

        val newPhishing = buildFilter(dao.getActiveDomainsByCategory("phishing"), 100_000)
        val newMalware  = dao.getActiveDomainsByCategory("malware")
        val newAdult    = buildFilter(dao.getActiveDomainsByCategory("adult"),    50_000)
        val newGambling = buildFilter(dao.getActiveDomainsByCategory("gambling"), 50_000)

        // Malware → cùng filter với phishing (luôn chặn)
        newMalware.forEach { newPhishing.add(it) }

        phishingFilter.set(newPhishing)
        adultFilter.set(newAdult)
        gamblingFilter.set(newGambling)
    }

    private fun loadFromAssets(context: Context) {
        phishingFilter.set(loadFile(context, FILE_PHISHING, 50_000))
        adultFilter.set(loadFile(context, FILE_ADULT,    20_000))
        gamblingFilter.set(loadFile(context, FILE_GAMBLING, 20_000))
    }

    private fun loadWhitelist(context: Context) {
        whitelist.clear()
        try {
            context.assets.open(FILE_WHITELIST).bufferedReader().useLines { lines ->
                lines.forEach { normalizeDomain(it)?.let { d -> whitelist.add(d) } }
            }
        } catch (_: Exception) {}
        whitelist.addAll(listOf("google.com", "android.com", "gstatic.com", "googleapis.com"))
    }

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
