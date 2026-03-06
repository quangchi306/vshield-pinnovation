package com.trustnet.vshield.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import com.trustnet.vshield.repository.BlocklistRepository
import com.trustnet.vshield.repository.SyncResult
import java.util.concurrent.TimeUnit

private const val TAG       = "SyncWorker"
private const val WORK_NAME = "vshield_blocklist_sync"

class BlocklistSyncWorker(
    context: Context,
    params:  WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "WorkManager: bắt đầu sync...")
        return when (val result = BlocklistRepository(applicationContext).sync()) {
            is SyncResult.Success -> {
                Log.i(TAG, "Sync OK: +${result.added} domains, version ${result.version}")
                Result.success()
            }
            is SyncResult.AlreadyUpToDate -> {
                Log.i(TAG, "Đã up-to-date.")
                Result.success()
            }
            is SyncResult.Error -> {
                Log.w(TAG, "Sync lỗi: ${result.message}")
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        }
    }

    companion object {

        /** Gọi trong VShieldApp.onCreate() */
        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            // Sync định kỳ mỗi 12h
            val periodicRequest = PeriodicWorkRequestBuilder<BlocklistSyncWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                periodicRequest,
            )

            // Chạy ngay mỗi lần app khởi động
            val immediateRequest = OneTimeWorkRequestBuilder<BlocklistSyncWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_initial",
                ExistingWorkPolicy.REPLACE,
                immediateRequest,
            )

            Log.i(TAG, "Sync lần đầu được kích hoạt ngay")
            Log.i(TAG, "Sync định kỳ đăng ký")
        }

        /** Gọi khi user nhấn "Cập nhật ngay" */
        fun syncNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<BlocklistSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_manual",
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}