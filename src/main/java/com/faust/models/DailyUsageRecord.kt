package com.faust.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 일일 사용 횟수를 추적하는 엔티티
 * 사용자 지정 시간 기준으로 날짜를 계산합니다.
 */
@Entity(tableName = "daily_usage_records")
data class DailyUsageRecord(
    @PrimaryKey
    val date: String,                         // 날짜 (YYYY-MM-DD 형식, 사용자 지정 시간 기준)
    val standardTicketUsedCount: Int = 0      // 스탠다드 티켓 일일 사용 횟수
)
