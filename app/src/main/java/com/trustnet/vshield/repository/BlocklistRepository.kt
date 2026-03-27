package com.trustnet.vshield.repository

import android.content.Context
import android.util.Log
import com.trustnet.vshield.core.DomainBlacklist
import com.trustnet.vshield.data.local.SyncPreferences
import com.trustnet.vshield.data.local.VShieldDatabase
import com.trustnet.vshield.data.local.entity.BlockedDomainEntity
import com.trustnet.vshield.data.local.entity.WhitelistedDomainEntity
import com.trustnet.vshield.data.remote.api.RetrofitClient
import com.trustnet.vshield.data.remote.model.RemoteDomain
import com.trustnet.vshield.data.remote.model.ReportRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "BlocklistRepo"

sealed class SyncResult {
    data class Success(val added: Int, val removed: Int, val version: Int) : SyncResult()
    data class Error(val message: String)                                   : SyncResult()
    object AlreadyUpToDate                                                  : SyncResult()
}

class BlocklistRepository(private val context: Context) {

    private val db           = VShieldDatabase.getInstance(context)
    private val dao          = db.blocklistDao()
    private val whitelistDao = db.whitelistDao()
    private val api          = RetrofitClient.api
    private val syncPrefs    = SyncPreferences(context)

    // Mutex chống 2 coroutine sync cùng lúc (WorkManager + SplashViewModel)
    private val syncMutex = Mutex()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * WorkManager gọi mỗi 12h.
     * Blocklist: full hoặc delta tùy needsFullSync
     * Whitelist: full nếu quá 7 ngày, delta nếu chưa
     *
     * FIX: Xử lý đúng cả trường hợp AlreadyUpToDate.
     * Nếu bin cache không tồn tại (bị xóa), phải reload từ DB và tạo lại cache
     * dù server báo "không có gì mới". Đây là nguyên nhân gốc rễ gây lỗi
     * whitelist/blacklist bị vô hiệu hóa vĩnh viễn sau khi xóa cache app.
     */
    suspend fun sync(): SyncResult = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                val result = if (syncPrefs.needsFullSync) fullSync() else deltaSync()

                when (result) {
                    is SyncResult.Success -> {
                        // Sync whitelist song song với reload BloomFilter
                        syncWhitelist()
                        DomainBlacklist.reloadFromDatabaseSync(context)
                        DomainBlacklist.saveToBinCache(context)
                    }

                    is SyncResult.AlreadyUpToDate -> {
                        // FIX LỖI CHÍNH: AlreadyUpToDate không có nghĩa là filter đã nạp!
                        // Kịch bản: xóa cache app → bin cache mất → mở app → deltaSync
                        // trả về AlreadyUpToDate → filter mãi là null → whitelist/blacklist
                        // không hoạt động và lỗi lặp lại mỗi lần mở app.
                        when {
                            !DomainBlacklist.isListsReady() -> {
                                // Filter chưa nạp vào RAM → nạp từ DB rồi tạo bin cache
                                Log.i(TAG, "AlreadyUpToDate nhưng filter chưa nạp — reload từ DB...")
                                DomainBlacklist.reloadFromDatabaseSync(context)
                                DomainBlacklist.saveToBinCache(context)
                            }
                            !DomainBlacklist.hasBinCache(context) -> {
                                // Filter đang có trong RAM nhưng bin cache mất → chỉ cần tạo lại cache
                                Log.i(TAG, "AlreadyUpToDate nhưng bin cache mất — tạo lại...")
                                DomainBlacklist.saveToBinCache(context)
                            }
                            else -> {
                                Log.i(TAG, "AlreadyUpToDate, filter OK, bin cache OK — bỏ qua.")
                            }
                        }
                    }

                    is SyncResult.Error -> { /* không làm gì, lỗi đã log bên dưới */ }
                }

                result
            } catch (e: Exception) {
                Log.e(TAG, "Sync thất bại: ${e.message}", e)
                SyncResult.Error(e.message ?: "Lỗi không xác định")
            }
        }
    }

    /**
     * Sync có báo cáo tiến trình — dùng trong SplashViewModel.
     *
     * FIX: Xử lý đúng AlreadyUpToDate giống hàm sync() ở trên.
     */
    suspend fun syncWithProgress(
        onProgress: suspend (Int, String) -> Unit
    ): SyncResult = syncMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                onProgress(10, "Đang kết nối máy chủ dữ liệu...")

                val result = if (syncPrefs.needsFullSync) {
                    onProgress(20, "Đang tải dữ liệu tên miền (Full Sync)...")
                    fullSync()
                } else {
                    onProgress(20, "Đang kiểm tra bản cập nhật (Delta Sync)...")
                    deltaSync()
                }

                when (result) {
                    is SyncResult.Success -> {
                        onProgress(50, "Đang đồng bộ danh sách cho phép...")
                        syncWhitelist()

                        onProgress(65, "Đang nạp bộ lọc vào bộ nhớ (RAM)...")
                        DomainBlacklist.reloadFromDatabaseSync(context)

                        onProgress(85, "Đang lưu cache bộ lọc (.bin)...")
                        DomainBlacklist.saveToBinCache(context)

                        onProgress(95, "Hoàn tất cập nhật dữ liệu.")
                    }

                    is SyncResult.AlreadyUpToDate -> {
                        // FIX LỖI CHÍNH (giống hàm sync() ở trên):
                        when {
                            !DomainBlacklist.isListsReady() -> {
                                onProgress(65, "Dữ liệu mới nhất. Đang nạp bộ lọc vào RAM...")
                                DomainBlacklist.reloadFromDatabaseSync(context)

                                onProgress(85, "Đang lưu cache bộ lọc (.bin)...")
                                DomainBlacklist.saveToBinCache(context)

                                onProgress(95, "Hoàn tất.")
                            }
                            !DomainBlacklist.hasBinCache(context) -> {
                                onProgress(85, "Đang tạo lại cache bộ lọc (.bin)...")
                                DomainBlacklist.saveToBinCache(context)
                                onProgress(95, "Hoàn tất.")
                            }
                            else -> {
                                onProgress(80, "Dữ liệu tên miền đã ở phiên bản mới nhất.")
                            }
                        }
                    }

                    is SyncResult.Error -> { /* xử lý ở SplashViewModel */ }
                }

                result
            } catch (e: Exception) {
                Log.e(TAG, "Sync thất bại: ${e.message}", e)
                onProgress(80, "Lỗi mạng. Bỏ qua cập nhật và dùng dữ liệu cũ.")
                SyncResult.Error(e.message ?: "Lỗi không xác định")
            }
        }
    }

    // ── Blocklist sync ────────────────────────────────────────────────────────

    private suspend fun fullSync(): SyncResult {
        Log.i(TAG, "Blocklist Full Sync bắt đầu...")

        dao.deleteAll()
        var page          = 1
        var totalInserted = 0
        var lastVersion   = 0

        while (true) {
            val response = api.getFullList(page = page, pageSize = 50000)
            if (!response.isSuccessful)
                return SyncResult.Error("HTTP ${response.code()}: ${response.message()}")

            val body = response.body()
                ?: return SyncResult.Error("Server trả về body rỗng")

            dao.insertAll(body.domains.map { it.toEntity() })
            totalInserted += body.domains.size
            lastVersion    = body.currentVersion

            Log.i(TAG, "Full Sync trang $page: +${body.domains.size} domains")

            if (!body.hasMore) break
            page++
        }

        syncPrefs.blocklistVersion = lastVersion
        syncPrefs.lastSyncTime     = System.currentTimeMillis()
        syncPrefs.needsFullSync    = false

        Log.i(TAG, "Blocklist Full Sync xong: $totalInserted domains, version $lastVersion")
        return SyncResult.Success(added = totalInserted, removed = 0, version = lastVersion)
    }

    private suspend fun deltaSync(): SyncResult {
        val currentVersion = syncPrefs.blocklistVersion
        Log.i(TAG, "Blocklist Delta Sync từ v$currentVersion...")

        val response = api.getDelta(since = currentVersion)
        if (!response.isSuccessful)
            return SyncResult.Error("HTTP ${response.code()}: ${response.message()}")

        val body = response.body()
            ?: return SyncResult.Error("Server trả về body rỗng")

        if (body.added.isNotEmpty())   dao.insertAll(body.added.map { it.toEntity() })
        if (body.removed.isNotEmpty()) dao.deactivateDomains(body.removed)

        if (body.totalAdded == 0 && body.totalRemoved == 0) {
            syncPrefs.lastSyncTime = System.currentTimeMillis()
            return SyncResult.AlreadyUpToDate
        }

        syncPrefs.blocklistVersion = body.currentVersion
        syncPrefs.lastSyncTime     = System.currentTimeMillis()

        Log.i(TAG, "Blocklist Delta xong: +${body.totalAdded}/-${body.totalRemoved}")
        return SyncResult.Success(
            added   = body.totalAdded,
            removed = body.totalRemoved,
            version = body.currentVersion,
        )
    }

    // ── Whitelist sync — tách riêng ───────────────────────────────────────────

    private suspend fun syncWhitelist() {
        try {
            if (syncPrefs.needsWhitelistFullSync) {
                whitelistFullSync()
            } else {
                whitelistDeltaSync()
            }
        } catch (e: Exception) {
            // Whitelist sync lỗi không làm crash toàn bộ sync
            Log.w(TAG, "Whitelist sync lỗi (bỏ qua): ${e.message}")
        }
    }

    private suspend fun whitelistFullSync() {
        Log.i(TAG, "Whitelist Full Sync bắt đầu...")

        val response = api.getWhitelistFull()
        if (!response.isSuccessful) {
            Log.w(TAG, "Whitelist Full Sync HTTP ${response.code()}")
            return
        }

        val body = response.body() ?: return

        whitelistDao.deleteAll()
        whitelistDao.insertAll(body.domains.mapIndexed { i, domain ->
            WhitelistedDomainEntity(domain = domain, version = i + 1)
        })

        syncPrefs.whitelistVersion      = body.whitelistVersion
        syncPrefs.lastWhitelistSyncTime = System.currentTimeMillis()

        Log.i(TAG, "Whitelist Full Sync xong: ${body.total} domains, version ${body.whitelistVersion}")
    }

    private suspend fun whitelistDeltaSync() {
        val currentVersion = syncPrefs.whitelistVersion
        Log.i(TAG, "Whitelist Delta Sync từ v$currentVersion...")

        val response = api.getWhitelistDelta(since = currentVersion)
        if (!response.isSuccessful) {
            Log.w(TAG, "Whitelist Delta HTTP ${response.code()}")
            return
        }

        val body = response.body() ?: return

        if (body.added.isEmpty()) {
            Log.i(TAG, "Whitelist đã mới nhất.")
            return
        }

        whitelistDao.insertAll(body.added.map { domain ->
            WhitelistedDomainEntity(domain = domain, version = body.whitelistVersion)
        })

        syncPrefs.whitelistVersion      = body.whitelistVersion
        syncPrefs.lastWhitelistSyncTime = System.currentTimeMillis()

        Log.i(TAG, "Whitelist Delta xong: +${body.totalAdded} domains")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    suspend fun reportDomain(domain: String, category: String): Result<String> =
        withContext(Dispatchers.IO) {
            try {
                val response = api.reportDomain(ReportRequest(domain, category))
                if (response.isSuccessful)
                    Result.success(response.body()?.message ?: "Đã ghi nhận")
                else
                    Result.failure(Exception("HTTP ${response.code()}"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun getTotalCount() = dao.countActive()
    fun getLastSyncTime()       = syncPrefs.lastSyncTime
    fun getCurrentVersion()     = syncPrefs.blocklistVersion

    private fun RemoteDomain.toEntity() = BlockedDomainEntity(
        domain   = domain,
        category = category,
        version  = version,
        isActive = isActive,
    )
}