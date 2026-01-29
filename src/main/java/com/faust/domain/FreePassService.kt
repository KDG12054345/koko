package com.faust.domain

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.faust.FaustApplication
import com.faust.data.database.FaustDatabase
import com.faust.data.utils.PreferenceManager
import com.faust.data.utils.TimeUtils
import com.faust.models.FreePassItem
import com.faust.models.FreePassItemType
import com.faust.models.PointTransaction
import com.faust.models.TransactionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 프리 패스 구매 및 사용 서비스
 * 아이템 구매, 사용, 누진 가격 계산, 쿨타임 관리를 담당합니다.
 */
class FreePassService(private val context: Context) {
    private val database: FaustDatabase by lazy {
        (context.applicationContext as FaustApplication).database
    }
    private val preferenceManager: PreferenceManager by lazy {
        PreferenceManager(context)
    }

    companion object {
        private const val TAG = "FreePassService"

        // 아이템별 가격 (기본 가격)
        private const val DOPAMINE_SHOT_PRICE = 15
        private const val STANDARD_TICKET_BASE_PRICE = 20  // 기본 가격 (0장일 때)
        private const val STANDARD_TICKET_PROGRESSIVE_INCREASE = 10  // 누진 증가분
        private const val STANDARD_TICKET_MAX_QUANTITY = 3
        private const val CINEMA_PASS_PRICE = 75
        private const val CINEMA_PASS_MAX_QUANTITY = 1

        // 쿨타임 (밀리초)
        private const val DOPAMINE_SHOT_COOLDOWN_MS = 30 * 60 * 1000L  // 30분
        private const val CINEMA_PASS_COOLDOWN_MS = 18 * 60 * 60 * 1000L  // 18시간

        // 하이브리드 쿨타임 (스탠다드 티켓)
        private const val STANDARD_TICKET_HYBRID_THRESHOLD = 3  // 일일 3회차
        private const val STANDARD_TICKET_HYBRID_COOLDOWN_MS = 60 * 60 * 1000L  // 1시간
    }

    /**
     * 구매 결과
     */
    sealed class PurchaseResult {
        data class Success(val item: FreePassItem) : PurchaseResult()
        data class Failure(val reason: String) : PurchaseResult()
    }

    /**
     * 사용 결과
     */
    sealed class UseResult {
        data class Success(val item: FreePassItem) : UseResult()
        data class Failure(val reason: String) : UseResult()
    }

    /**
     * 아이템을 구매합니다.
     * 
     * @param itemType 구매할 아이템 타입
     * @return 구매 결과
     */
    suspend fun purchaseItem(itemType: FreePassItemType): PurchaseResult {
        return withContext(Dispatchers.IO) {
            try {
                // 1. 구매 가능 여부 확인
                if (!canPurchase(itemType)) {
                    val reason = when {
                        !hasEnoughPoints(itemType) -> "포인트가 부족합니다"
                        !canPurchaseByCooldown(itemType) -> "쿨타임이 남아있습니다"
                        !canPurchaseByInventory(itemType) -> "인벤토리 한도에 도달했습니다"
                        else -> "구매할 수 없습니다"
                    }
                    return@withContext PurchaseResult.Failure(reason)
                }

                // 2. 가격 계산
                val price = when (itemType) {
                    FreePassItemType.DOPAMINE_SHOT -> DOPAMINE_SHOT_PRICE
                    FreePassItemType.STANDARD_TICKET -> {
                        val currentItem = database.freePassItemDao().getItem(itemType)
                        calculateProgressivePrice(itemType, currentItem?.quantity ?: 0)
                    }
                    FreePassItemType.CINEMA_PASS -> CINEMA_PASS_PRICE
                }

                // 3. 트랜잭션으로 구매 처리
                var purchasedItem: FreePassItem? = null
                database.withTransaction {
                    try {
                        // 현재 포인트 확인
                        val currentPoints = database.pointTransactionDao().getTotalPoints() ?: 0
                        if (currentPoints < price) {
                            throw IllegalStateException("포인트가 부족합니다")
                        }

                        // 포인트 차감
                        database.pointTransactionDao().insertTransaction(
                            PointTransaction(
                                amount = -price,
                                type = TransactionType.PURCHASE,
                                reason = "${itemType.name} 구매"
                            )
                        )

                        // 포인트 동기화
                        val newPoints = currentPoints - price
                        preferenceManager.setCurrentPoints(newPoints)

                        // 아이템 업데이트
                        val currentItem = database.freePassItemDao().getItem(itemType)
                        val now = System.currentTimeMillis()
                        purchasedItem = when (itemType) {
                            FreePassItemType.DOPAMINE_SHOT -> {
                                // 도파민 샷은 구매와 동시에 사용되므로 수량 증가 없음
                                // lastUseTime을 now로 설정하여 즉시 사용 처리
                                FreePassItem(
                                    itemType = itemType,
                                    quantity = 0,
                                    lastPurchaseTime = now,
                                    lastUseTime = now  // 구매와 동시에 사용
                                )
                            }
                            FreePassItemType.STANDARD_TICKET -> {
                                FreePassItem(
                                    itemType = itemType,
                                    quantity = (currentItem?.quantity ?: 0) + 1,
                                    lastPurchaseTime = now,
                                    lastUseTime = currentItem?.lastUseTime ?: 0L
                                )
                            }
                            FreePassItemType.CINEMA_PASS -> {
                                FreePassItem(
                                    itemType = itemType,
                                    quantity = 1,  // 최대 1장
                                    lastPurchaseTime = now,
                                    lastUseTime = currentItem?.lastUseTime ?: 0L
                                )
                            }
                        }
                        database.freePassItemDao().insertOrUpdateItem(purchasedItem!!)

                        Log.d(TAG, "아이템 구매 완료: $itemType, 가격: $price WP")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error purchasing item: $itemType", e)
                        throw e // 트랜잭션 롤백
                    }
                }

                PurchaseResult.Success(purchasedItem!!)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to purchase item: $itemType", e)
                PurchaseResult.Failure(e.message ?: "구매 실패")
            }
        }
    }

    /**
     * 아이템을 사용합니다.
     * 
     * @param itemType 사용할 아이템 타입
     * @return 사용 결과
     */
    suspend fun useItem(itemType: FreePassItemType): UseResult {
        return withContext(Dispatchers.IO) {
            try {
                val currentItem = database.freePassItemDao().getItem(itemType)
                    ?: return@withContext UseResult.Failure("보유한 아이템이 없습니다")

                // 스탠다드 티켓은 수량 확인
                if (itemType == FreePassItemType.STANDARD_TICKET && currentItem.quantity <= 0) {
                    return@withContext UseResult.Failure("보유한 티켓이 없습니다")
                }

                // 하이브리드 쿨타임 확인 (스탠다드 티켓만)
                if (itemType == FreePassItemType.STANDARD_TICKET) {
                    val customTime = preferenceManager.getCustomDailyResetTime()
                    val todayRecord = getTodayUsageRecord(customTime)
                    val usedCount = todayRecord?.standardTicketUsedCount ?: 0

                    if (usedCount >= STANDARD_TICKET_HYBRID_THRESHOLD) {
                        // 3회차 초과 시 쿨타임 확인
                        val lastUseTime = currentItem.lastUseTime
                        if (lastUseTime > 0) {
                            val elapsed = System.currentTimeMillis() - lastUseTime
                            if (elapsed < STANDARD_TICKET_HYBRID_COOLDOWN_MS) {
                                val remainingMinutes = (STANDARD_TICKET_HYBRID_COOLDOWN_MS - elapsed) / (60 * 1000)
                                return@withContext UseResult.Failure("쿨타임이 남아있습니다 (${remainingMinutes}분)")
                            }
                        }
                    }
                }

                // 아이템 사용 처리
                val now = System.currentTimeMillis()
                val updatedItem = when (itemType) {
                    FreePassItemType.DOPAMINE_SHOT -> {
                        // 도파민 샷은 구매와 동시에 실행되므로 여기서는 사용 시간만 업데이트
                        currentItem.copy(lastUseTime = now)
                    }
                    FreePassItemType.STANDARD_TICKET -> {
                        // 수량 감소 및 사용 시간 업데이트
                        val newQuantity = (currentItem.quantity - 1).coerceAtLeast(0)
                        currentItem.copy(
                            quantity = newQuantity,
                            lastUseTime = now
                        )
                    }
                    FreePassItemType.CINEMA_PASS -> {
                        // 시네마 패스는 수량 감소 없이 사용 시간만 업데이트
                        currentItem.copy(lastUseTime = now)
                    }
                }

                database.freePassItemDao().insertOrUpdateItem(updatedItem)

                // 일일 사용 횟수 업데이트 (스탠다드 티켓만)
                if (itemType == FreePassItemType.STANDARD_TICKET) {
                    updateDailyUsageRecord()
                }

                Log.d(TAG, "아이템 사용 완료: $itemType")
                UseResult.Success(updatedItem)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to use item: $itemType", e)
                UseResult.Failure(e.message ?: "사용 실패")
            }
        }
    }

    /**
     * 누진 가격을 계산합니다.
     * 
     * @param itemType 아이템 타입
     * @param currentQuantity 현재 보유 수량
     * @return 가격
     */
    fun calculateProgressivePrice(itemType: FreePassItemType, currentQuantity: Int): Int {
        return when (itemType) {
            FreePassItemType.STANDARD_TICKET -> {
                STANDARD_TICKET_BASE_PRICE + (currentQuantity * STANDARD_TICKET_PROGRESSIVE_INCREASE)
            }
            FreePassItemType.DOPAMINE_SHOT -> DOPAMINE_SHOT_PRICE
            FreePassItemType.CINEMA_PASS -> CINEMA_PASS_PRICE
        }
    }

    /**
     * 구매 가능 여부를 확인합니다.
     */
    suspend fun canPurchase(itemType: FreePassItemType): Boolean {
        return withContext(Dispatchers.IO) {
            hasEnoughPoints(itemType) &&
                    canPurchaseByCooldown(itemType) &&
                    canPurchaseByInventory(itemType)
        }
    }

    /**
     * 잔여 쿨타임을 반환합니다 (밀리초).
     */
    suspend fun getRemainingCooldown(itemType: FreePassItemType): Long {
        return withContext(Dispatchers.IO) {
            val item = database.freePassItemDao().getItem(itemType) ?: return@withContext 0L
            val lastPurchaseTime = item.lastPurchaseTime
            if (lastPurchaseTime <= 0) return@withContext 0L

            val cooldown = when (itemType) {
                FreePassItemType.DOPAMINE_SHOT -> DOPAMINE_SHOT_COOLDOWN_MS
                FreePassItemType.CINEMA_PASS -> CINEMA_PASS_COOLDOWN_MS
                FreePassItemType.STANDARD_TICKET -> 0L  // 스탠다드 티켓은 재구매 쿨타임 없음
            }

            if (cooldown == 0L) return@withContext 0L

            val elapsed = System.currentTimeMillis() - lastPurchaseTime
            (cooldown - elapsed).coerceAtLeast(0L)
        }
    }

    /**
     * 포인트가 충분한지 확인합니다.
     */
    private suspend fun hasEnoughPoints(itemType: FreePassItemType): Boolean {
        val currentPoints = database.pointTransactionDao().getTotalPoints() ?: 0
        val currentItem = database.freePassItemDao().getItem(itemType)
        val price = calculateProgressivePrice(itemType, currentItem?.quantity ?: 0)
        return currentPoints >= price
    }

    /**
     * 쿨타임으로 인해 구매 가능한지 확인합니다.
     */
    private suspend fun canPurchaseByCooldown(itemType: FreePassItemType): Boolean {
        return getRemainingCooldown(itemType) == 0L
    }

    /**
     * 인벤토리 한도로 인해 구매 가능한지 확인합니다.
     */
    private suspend fun canPurchaseByInventory(itemType: FreePassItemType): Boolean {
        val item = database.freePassItemDao().getItem(itemType)
        return when (itemType) {
            FreePassItemType.STANDARD_TICKET -> {
                (item?.quantity ?: 0) < STANDARD_TICKET_MAX_QUANTITY
            }
            FreePassItemType.CINEMA_PASS -> {
                (item?.quantity ?: 0) < CINEMA_PASS_MAX_QUANTITY
            }
            FreePassItemType.DOPAMINE_SHOT -> true  // 도파민 샷은 인벤토리 없음
        }
    }

    /**
     * 오늘 날짜의 사용 기록을 가져옵니다.
     */
    private suspend fun getTodayUsageRecord(customTime: String): com.faust.models.DailyUsageRecord? {
        val today = TimeUtils.getDayString(customTime)
        return database.dailyUsageRecordDao().getTodayRecord(today)
    }

    /**
     * 일일 사용 기록을 업데이트합니다.
     */
    private suspend fun updateDailyUsageRecord() {
        val customTime = preferenceManager.getCustomDailyResetTime()
        val today = TimeUtils.getDayString(customTime)
        val record = database.dailyUsageRecordDao().getTodayRecord(today)
            ?: com.faust.models.DailyUsageRecord(date = today, standardTicketUsedCount = 0)

        val updatedRecord = record.copy(
            standardTicketUsedCount = record.standardTicketUsedCount + 1
        )
        database.dailyUsageRecordDao().insertOrUpdateRecord(updatedRecord)
    }
}
