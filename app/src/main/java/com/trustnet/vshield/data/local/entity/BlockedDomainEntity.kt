package com.trustnet.vshield.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "blocked_domains",
    indices = [
        Index(value = ["domain"], unique = true),
        Index(value = ["category"]),
    ]
)
data class BlockedDomainEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,

    @ColumnInfo(name = "domain")
    val domain: String,

    @ColumnInfo(name = "category")
    val category: String,

    @ColumnInfo(name = "version")
    val version: Int,

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true,
)
