package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class SentinelTypeConverters {
    @TypeConverter
    fun fromAuthType(value: AuthType): String = value.name

    @TypeConverter
    fun toAuthType(value: String): AuthType = runCatching { AuthType.valueOf(value) }.getOrDefault(AuthType.PASSWORD)
}

@Database(
    entities = [ServerProfileEntity::class, CommandSnippetEntity::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(SentinelTypeConverters::class)
abstract class SentinelDatabase : RoomDatabase() {
    abstract fun serverProfileDao(): ServerProfileDao
    abstract fun commandSnippetDao(): CommandSnippetDao

    companion object {
        @Volatile
        private var INSTANCE: SentinelDatabase? = null

        fun getInstance(context: Context): SentinelDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SentinelDatabase::class.java,
                    "sentinel_vps.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
