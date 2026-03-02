package com.trustnet.vshield.repository

import android.content.Context
import android.util.Log
import com.trustnet.vshield.core.DomainBlacklist
import com.trustnet.vshield.data.local.SyncPreferences
import com.trustnet.vshield.data.local.VShieldDatabase
import com.trustnet.vshield.data.local.entity.BlockedDomainEntity
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

    private val dao       = VShieldDatabase.getInstance(context).blocklistDao()
    private val api       = RetrofitClient.api
    private val syncPrefs = SyncPreferences(context)

    /**
     * Hàm chính — WorkManager gọi mỗi 12h.
     * 1. Nếu chưa có data → Full sync
     * 2. Đã có data → Delta sync (chỉ lấy phần thay đổi)
     * 3. Sau sync → Reload BloomFilter trong DomainBlacklist
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

    private suspend fun fullSync(): SyncResult {
        Log.i(TAG, "Full Sync bắt đầu...")
        val response = api.getFullList()
        if (!response.isSuccessful)
            return SyncResult.Error("HTTP ${response.code()}: ${response.message()}")

        val body = response.body()
            ?: return SyncResult.Error("Server trả về body rỗng")

        dao.deleteAll()
        dao.insertAll(body.domains.map { it.toEntity() })
        syncPrefs.blocklistVersion = body.currentVersion
        syncPrefs.lastSyncTime     = System.currentTimeMillis()
        syncPrefs.needsFullSync    = false

        Log.i(TAG, "Full Sync xong: ${body.total} domains, version ${body.currentVersion}")
        return SyncResult.Success(added = body.total, removed = 0, version = body.currentVersion)
    }

    private suspend fun deltaSync(): SyncResult {
        val currentVersion = syncPrefs.blocklistVersion
        Log.i(TAG, "Delta Sync từ version $currentVersion...")

        val response = api.getDelta(since = currentVersion)
        if (!response.isSuccessful)
            return SyncResult.Error("HTTP ${response.code()}: ${response.message()}")

        val body = response.body()
            ?: return SyncResult.Error("Server trả về body rỗng")

        if (body.totalAdded == 0 && body.totalRemoved == 0) {
            syncPrefs.lastSyncTime = System.currentTimeMillis()
            return SyncResult.AlreadyUpToDate
        }

        if (body.added.isNotEmpty())   dao.insertAll(body.added.map { it.toEntity() })
        if (body.removed.isNotEmpty()) dao.deactivateDomains(body.removed)

        syncPrefs.blocklistVersion = body.currentVersion
        syncPrefs.lastSyncTime     = System.currentTimeMillis()

        Log.i(TAG, "Delta Sync xong: +${body.totalAdded}/-${body.totalRemoved}, version ${body.currentVersion}")
        return SyncResult.Success(
            added   = body.totalAdded,
            removed = body.totalRemoved,
            version = body.currentVersion,
        )
    }

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

    suspend fun getTotalCount()     = dao.countActive()
    fun getLastSyncTime()           = syncPrefs.lastSyncTime
    fun getCurrentVersion()         = syncPrefs.blocklistVersion

    private fun RemoteDomain.toEntity() = BlockedDomainEntity(
        domain   = domain,
        category = category,
        version  = version,
        isActive = isActive,
    )
}
