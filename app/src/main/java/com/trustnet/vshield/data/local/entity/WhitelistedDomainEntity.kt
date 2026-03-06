package com.trustnet.vshield.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "whitelisted_domains",
    indices   = [Index(value = ["domain"], unique = true)]
)
data class WhitelistedDomainEntity(
    @PrimaryKey val domain: String,
    val version: Int,
)