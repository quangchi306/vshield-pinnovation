package com.trustnet.vshield.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.trustnet.vshield.data.local.entity.WhitelistedDomainEntity

@Dao
interface WhitelistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(domains: List<WhitelistedDomainEntity>)

    @Query("SELECT domain FROM whitelisted_domains")
    suspend fun getAllDomains(): List<String>

    @Query("SELECT COUNT(*) FROM whitelisted_domains")
    suspend fun count(): Int

    @Query("DELETE FROM whitelisted_domains")
    suspend fun deleteAll()
}