package com.faust.data.database

import androidx.room.*
import com.faust.models.DailyUsageRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyUsageRecordDao {
    /**
     * 모든 일일 사용 기록을 Flow로 반환합니다.
     */
    @Query("SELECT * FROM daily_usage_records ORDER BY date DESC")
    fun getAllRecords(): Flow<List<DailyUsageRecord>>

    /**
     * 특정 날짜의 기록을 조회합니다.
     */
    @Query("SELECT * FROM daily_usage_records WHERE date = :date")
    suspend fun getRecord(date: String): DailyUsageRecord?

    /**
     * 특정 날짜의 기록을 Flow로 조회합니다.
     */
    @Query("SELECT * FROM daily_usage_records WHERE date = :date")
    fun getRecordFlow(date: String): Flow<DailyUsageRecord?>

    /**
     * 오늘 날짜의 기록을 조회합니다 (사용자 지정 시간 기준).
     */
    @Query("SELECT * FROM daily_usage_records WHERE date = :date")
    suspend fun getTodayRecord(date: String): DailyUsageRecord?

    /**
     * 기록을 삽입하거나 업데이트합니다.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateRecord(record: DailyUsageRecord)

    /**
     * 기록을 삭제합니다.
     */
    @Delete
    suspend fun deleteRecord(record: DailyUsageRecord)

    /**
     * 모든 기록을 삭제합니다.
     */
    @Query("DELETE FROM daily_usage_records")
    suspend fun deleteAllRecords()
}
