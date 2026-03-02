package com.trustnet.vshield.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.trustnet.vshield.data.local.dao.BlocklistDao
import com.trustnet.vshield.data.local.entity.BlockedDomainEntity

@Database(
    entities = [BlockedDomainEntity::class],
    version  = 1,
    exportSchema = false,
)
abstract class VShieldDatabase : RoomDatabase() {

    abstract fun blocklistDao(): BlocklistDao

    companion object {
        @Volatile private var INSTANCE: VShieldDatabase? = null

        fun getInstance(context: Context): VShieldDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    VShieldDatabase::class.java,
                    "vshield_blocklist.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
