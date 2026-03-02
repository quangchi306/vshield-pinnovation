package com.trustnet.vshield.data.local.dao

import androidx.room.*
import com.trustnet.vshield.data.local.entity.BlockedDomainEntity

@Dao
interface BlocklistDao {

    @Query("SELECT domain FROM blocked_domains WHERE is_active = 1 AND category = :category")
    suspend fun getActiveDomainsByCategory(category: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(domains: List<BlockedDomainEntity>)

    @Query("UPDATE blocked_domains SET is_active = 0 WHERE domain IN (:domains)")
    suspend fun deactivateDomains(domains: List<String>)

    @Query("DELETE FROM blocked_domains")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM blocked_domains WHERE is_active = 1")
    suspend fun countActive(): Int

    @Query("SELECT COUNT(*) FROM blocked_domains WHERE is_active = 1 AND category = :category")
    suspend fun countByCategory(category: String): Int
}
