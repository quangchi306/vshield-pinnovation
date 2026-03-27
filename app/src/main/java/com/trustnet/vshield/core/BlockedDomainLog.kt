package com.trustnet.vshield.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory log của các tên miền vừa bị chặn.
 *
 * Quy tắc:
 *  - Mỗi domain chỉ xuất hiện 1 lần — nếu bị chặn lại thì entry cũ bị xóa,
 *    entry mới được thêm vào đầu danh sách (newest-first).
 *  - Mỗi entry tự động hết hạn sau TTL_MS (5 phút).
 *  - Thread-safe: mọi thao tác đều đi qua synchronized(map).
 */
object BlockedDomainLog {

    const val TTL_MS = 5 * 60 * 1000L   // 5 phút

    // Nguồn gốc chặn
    enum class Source { BLACKLIST, AI }

    data class BlockedEntry(
        val domain: String,
        val reason: String,
        val source: Source,
        val blockedAt: Long = System.currentTimeMillis()
    ) {
        val expireAt: Long get() = blockedAt + TTL_MS
    }

    // LinkedHashMap: key = domain (dedup O(1)), duyệt theo thứ tự chèn
    private val map = LinkedHashMap<String, BlockedEntry>()

    private val _entries = MutableStateFlow<List<BlockedEntry>>(emptyList())
    val entries: StateFlow<List<BlockedEntry>> = _entries.asStateFlow()

    /**
     * Thêm 1 entry mới.
     * Nếu domain đã có trong log → xóa entry cũ trước, chèn mới → tự động lên đầu.
     */
    fun add(domain: String, reason: String, source: Source) {
        synchronized(map) {
            map.remove(domain)         // bỏ entry cũ nếu tồn tại → re-insert vào cuối map
            purgeExpiredLocked()       // dọn entry hết hạn trước khi thêm
            map[domain] = BlockedEntry(domain, reason, source)
            publish()
        }
    }

    /**
     * Người dùng bấm "Bỏ qua" → xóa khỏi log ngay lập tức.
     * Caller còn phải gọi DomainBlacklist.addTemporaryAllow(domain) để VPN bỏ chặn.
     */
    fun bypass(domain: String) {
        synchronized(map) {
            map.remove(domain)
            publish()
        }
    }

    /**
     * Gọi định kỳ từ UI (LaunchedEffect) để dọn entry hết TTL.
     * Không emit StateFlow nếu danh sách không thay đổi (tránh recompose thừa).
     */
    fun tick() {
        synchronized(map) {
            val before = map.size
            purgeExpiredLocked()
            if (map.size != before) publish()
        }
    }

    // ─── Private ───────────────────────────────────────────────────────────────

    private fun purgeExpiredLocked() {
        val now = System.currentTimeMillis()
        val iter = map.iterator()
        while (iter.hasNext()) {
            if (now > iter.next().value.expireAt) iter.remove()
        }
    }

    /** Publish list mới nhất — reversed() để entry mới nhất lên đầu */
    private fun publish() {
        _entries.value = map.values.reversed().toList()
    }
}