package com.trustnet.vshield.network

import android.util.LruCache

object AiResultCache {
    // Lưu tối đa 1000 domain gần nhất trong RAM.
    // LruCache sẽ tự động xóa các domain cũ nhất nếu vượt quá 1000, giúp app không bao giờ bị tràn RAM.
    private val cache = LruCache<String, CacheItem>(1000)

    // Data class lưu trữ kết quả chặn và thời gian hết hạn của kết quả đó
    data class CacheItem(val isBlocked: Boolean, val expireTime: Long)

    /**
     * Lấy kết quả từ Cache.
     * Trả về TRUE (Bị chặn), FALSE (Sạch), hoặc NULL (Chưa có trong cache hoặc đã hết hạn).
     */
    fun get(domain: String): Boolean? {
        val item = cache.get(domain) ?: return null

        // Kiểm tra xem kết quả lưu trữ đã quá hạn chưa
        if (System.currentTimeMillis() > item.expireTime) {
            cache.remove(domain) // Xóa kết quả cũ
            return null          // Báo null để app tự đi crawl và gọi API lại
        }

        return item.isBlocked
    }

    /**
     * Lưu kết quả mới vào Cache sau khi nhận phản hồi từ AI Server.
     */
    fun put(domain: String, isBlocked: Boolean) {
        // Thời gian sống của Cache: 10 phút (600.000 mili-giây).
        // Sau 10 phút, nếu người dùng vào lại web này, hệ thống sẽ quét lại để cập nhật tình trạng mới nhất.
        val expire = System.currentTimeMillis() + 600_000
        cache.put(domain, CacheItem(isBlocked, expire))
    }
}