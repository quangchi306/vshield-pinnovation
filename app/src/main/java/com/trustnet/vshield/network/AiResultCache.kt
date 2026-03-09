package com.trustnet.vshield.network

import android.util.LruCache

object AiResultCache {
    private val cache = LruCache<String, CacheItem>(1000)

    // Lưu trữ kết quả chặn, lý do chặn và thời gian hết hạn
    data class CacheItem(val isBlocked: Boolean, val reason: String, val expireTime: Long)

    // Data class để trả về kết quả
    data class AiDecision(val isBlocked: Boolean, val reason: String)

    fun get(domain: String): AiDecision? {
        val item = cache.get(domain) ?: return null

        if (System.currentTimeMillis() > item.expireTime) {
            cache.remove(domain)
            return null
        }

        return AiDecision(item.isBlocked, item.reason)
    }

    fun put(domain: String, isBlocked: Boolean, reason: String = "") {
        val expire = System.currentTimeMillis() + 600_000
        cache.put(domain, CacheItem(isBlocked, reason, expire))
    }
}