package com.faust.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 프리 패스 아이템 정보를 저장하는 엔티티
 */
@Entity(tableName = "free_pass_items")
data class FreePassItem(
    @PrimaryKey
    val itemType: FreePassItemType,
    val quantity: Int = 0,                    // 보유 수량
    val lastPurchaseTime: Long = 0L,          // 마지막 구매 시간 (timestamp)
    val lastUseTime: Long = 0L                // 마지막 사용 시간 (timestamp)
)
