package com.faust.data.database

import androidx.room.*
import com.faust.models.FreePassItem
import com.faust.models.FreePassItemType
import kotlinx.coroutines.flow.Flow

@Dao
interface FreePassItemDao {
    /**
     * 모든 프리 패스 아이템을 Flow로 반환합니다.
     */
    @Query("SELECT * FROM free_pass_items")
    fun getAllItems(): Flow<List<FreePassItem>>

    /**
     * 특정 타입의 아이템을 조회합니다.
     */
    @Query("SELECT * FROM free_pass_items WHERE itemType = :itemType")
    suspend fun getItem(itemType: FreePassItemType): FreePassItem?

    /**
     * 특정 타입의 아이템을 Flow로 조회합니다.
     */
    @Query("SELECT * FROM free_pass_items WHERE itemType = :itemType")
    fun getItemFlow(itemType: FreePassItemType): Flow<FreePassItem?>

    /**
     * 아이템을 삽입하거나 업데이트합니다.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateItem(item: FreePassItem)

    /**
     * 아이템을 삭제합니다.
     */
    @Delete
    suspend fun deleteItem(item: FreePassItem)

    /**
     * 모든 아이템을 삭제합니다.
     */
    @Query("DELETE FROM free_pass_items")
    suspend fun deleteAllItems()
}
