package com.faust.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.faust.models.AppGroup
import com.faust.models.BlockedApp
import com.faust.models.DailyUsageRecord
import com.faust.models.FreePassItem
import com.faust.models.PointTransaction

@Database(
    entities = [
        BlockedApp::class,
        PointTransaction::class,
        FreePassItem::class,
        DailyUsageRecord::class,
        AppGroup::class
    ],
    version = 2,
    exportSchema = false
)
abstract class FaustDatabase : RoomDatabase() {
    abstract fun appBlockDao(): AppBlockDao
    abstract fun pointTransactionDao(): PointTransactionDao
    abstract fun freePassItemDao(): FreePassItemDao
    abstract fun dailyUsageRecordDao(): DailyUsageRecordDao
    abstract fun appGroupDao(): AppGroupDao

    companion object {
        @Volatile
        private var INSTANCE: FaustDatabase? = null

        /**
         * 버전 1에서 2로의 Migration
         * 새 테이블 추가: free_pass_items, daily_usage_records, app_groups
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // free_pass_items 테이블 생성
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS free_pass_items (
                        itemType TEXT NOT NULL PRIMARY KEY,
                        quantity INTEGER NOT NULL DEFAULT 0,
                        lastPurchaseTime INTEGER NOT NULL DEFAULT 0,
                        lastUseTime INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                // daily_usage_records 테이블 생성
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS daily_usage_records (
                        date TEXT NOT NULL PRIMARY KEY,
                        standardTicketUsedCount INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                // app_groups 테이블 생성
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS app_groups (
                        packageName TEXT NOT NULL,
                        groupType TEXT NOT NULL,
                        isIncluded INTEGER NOT NULL DEFAULT 1,
                        PRIMARY KEY (packageName, groupType)
                    )
                """.trimIndent())
            }
        }

        fun getDatabase(context: Context): FaustDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    FaustDatabase::class.java,
                    "faust_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration() // Migration 실패 시 폴백
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
