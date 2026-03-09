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

    /**
     *WorkManager call 12h/1.
     * 1. Chưa có data -> Full sync (blocklist + whitelist)
     * 2. Đã có data -> data Delta sync
     * 3. Sau sync -> Reload DomainBlacklist (BloomFilter + HashSet)
     */
    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        try {
            val result = if (syncPrefs.needsFullSync) fullSync() else deltaSync()
            if (result is SyncResult.Success) {
                DomainBlacklist.reloadFromDatabase(context)
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Sync thất bại: ${e.message}", e)
            SyncResult.Error(e.message ?: "Lỗi không xác định")
        }
    }

    // ── Full sync ──────────────────────────────────────────────────────────
    private suspend fun fullSync(): SyncResult {
        Log.i(TAG, "Full Sync bắt đầu...")
        val response = api.getFullList()
        if (!response.isSuccessful)
            return SyncResult.Error("HTTP ${response.code()}: ${response.message()}")

        val body = response.body()
            ?: return SyncResult.Error("Server trả về body rỗng")

        // Lưu blocklist
        dao.deleteAll()
        dao.insertAll(body.domains.map { it.toEntity() })

        // Lưu whitelist
        if (body.whitelisted.isNotEmpty()) {
            whitelistDao.deleteAll()
            whitelistDao.insertAll(body.whitelisted.mapIndexed { i, domain ->
                WhitelistedDomainEntity(domain = domain, version = i + 1)
            })
            Log.i(TAG, "Whitelist: ${body.whitelisted.size} domains")
        }

        syncPrefs.blocklistVersion  = body.currentVersion
        syncPrefs.whitelistVersion  = body.totalWhitelisted
        syncPrefs.lastSyncTime      = System.currentTimeMillis()
        syncPrefs.needsFullSync     = false

        Log.i(TAG, "Full Sync xong: ${body.total} blocklist + ${body.whitelisted.size} whitelist, version ${body.currentVersion}")
        return SyncResult.Success(added = body.total, removed = 0, version = body.currentVersion)
    }

    //Delta sync
    private suspend fun deltaSync(): SyncResult {
        val currentVersion   = syncPrefs.blocklistVersion
        val whitelistVersion = syncPrefs.whitelistVersion
        Log.i(TAG, "Delta Sync từ blocklist v$currentVersion, whitelist v$whitelistVersion...")

        val response = api.getDelta(
            since           = currentVersion,
            whitelistSince  = whitelistVersion,
        )
        if (!response.isSuccessful)
            return SyncResult.Error("HTTP ${response.code()}: ${response.message()}")

        val body = response.body()
            ?: return SyncResult.Error("Server trả về body rỗng")

        // Cập nhật blocklist
        if (body.added.isNotEmpty())   dao.insertAll(body.added.map { it.toEntity() })
        if (body.removed.isNotEmpty()) dao.deactivateDomains(body.removed)

        // Cập nhật whitelist (chỉ append domain mới)
        if (body.whitelisted.isNotEmpty()) {
            whitelistDao.insertAll(body.whitelisted.map { domain ->
                WhitelistedDomainEntity(domain = domain, version = body.whitelistVersion)
            })
            syncPrefs.whitelistVersion = body.whitelistVersion
            Log.i(TAG, "Whitelist delta: +${body.whitelisted.size} domains")
        }

        if (body.totalAdded == 0 && body.totalRemoved == 0 && body.totalWhitelisted == 0) {
            syncPrefs.lastSyncTime = System.currentTimeMillis()
            return SyncResult.AlreadyUpToDate
        }

        syncPrefs.blocklistVersion = body.currentVersion
        syncPrefs.lastSyncTime     = System.currentTimeMillis()

        Log.i(TAG, "Delta Sync xong: +${body.totalAdded}/-${body.totalRemoved} blocklist, +${body.totalWhitelisted} whitelist")
        return SyncResult.Success(
            added   = body.totalAdded,
            removed = body.totalRemoved,
            version = body.currentVersion,
        )
    }

    //Helpers
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
    // --- THÊM HÀM NÀY VÀO CUỐI CLASS BlocklistRepository ---
    suspend fun syncWithProgress(onProgress: suspend (Int, String) -> Unit): SyncResult = withContext(Dispatchers.IO) {
        try {
            onProgress(10, "Đang kết nối máy chủ dữ liệu...")

            // Tái sử dụng lại logic Sync cũ
            val result = if (syncPrefs.needsFullSync) {
                onProgress(20, "Đang tải dữ liệu tên miền (Full Sync)...")
                fullSync()
            } else {
                onProgress(20, "Đang kiểm tra bản cập nhật (Delta Sync)...")
                deltaSync()
            }

            if (result is SyncResult.Success) {
                onProgress(70, "Đang nạp bộ lọc vào bộ nhớ (RAM)...")
                DomainBlacklist.reloadFromDatabase(context)
            } else if (result is SyncResult.AlreadyUpToDate) {
                onProgress(70, "Dữ liệu tên miền đã ở phiên bản mới nhất.")
            }

            result
        } catch (e: Exception) {
            Log.e(TAG, "Sync thất bại: ${e.message}", e)
            onProgress(70, "Lỗi mạng. Bỏ qua cập nhật và dùng dữ liệu cũ.")
            SyncResult.Error(e.message ?: "Lỗi không xác định")
        }
    }
}