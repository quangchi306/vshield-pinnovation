package com.trustnet.vshield.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object BlockedDomainLog {

    const val TTL_MS = 5 * 60 * 1000L

    enum class Source { BLACKLIST, AI }

    data class BlockedEntry(
        val domain: String,
        val reason: String,
        val source: Source,
        val category: DomainBlacklist.BlockCategory,
        val blockedAt: Long = System.currentTimeMillis()
    ) {
        val expireAt: Long get() = blockedAt + TTL_MS
        val canBypass: Boolean
            get() = category == DomainBlacklist.BlockCategory.ADULT ||
                    category == DomainBlacklist.BlockCategory.GAMBLING
    }

    private val map = LinkedHashMap<String, BlockedEntry>()

    private val _entries = MutableStateFlow<List<BlockedEntry>>(emptyList())
    val entries: StateFlow<List<BlockedEntry>> = _entries.asStateFlow()

    fun add(
        domain: String,
        reason: String,
        source: Source,
        category: DomainBlacklist.BlockCategory
    ) {
        synchronized(map) {
            map.remove(domain)
            purgeExpiredLocked()
            map[domain] = BlockedEntry(
                domain = domain,
                reason = reason,
                source = source,
                category = category
            )
            publish()
        }
    }

    fun bypass(domain: String) {
        synchronized(map) {
            map.remove(domain)
            publish()
        }
    }

    fun tick() {
        synchronized(map) {
            val before = map.size
            purgeExpiredLocked()
            if (map.size != before) publish()
        }
    }

    private fun purgeExpiredLocked() {
        val now = System.currentTimeMillis()
        val iter = map.iterator()
        while (iter.hasNext()) {
            if (now > iter.next().value.expireAt) iter.remove()
        }
    }

    private fun publish() {
        _entries.value = map.values.reversed().toList()
    }
}