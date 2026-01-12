package com.faust.services

import android.content.Context
import androidx.room.withTransaction
import com.faust.FaustApplication
import com.faust.database.FaustDatabase
import com.faust.models.TransactionType
import com.faust.models.UserTier
import com.faust.utils.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PenaltyService(private val context: Context) {
    private val database: FaustDatabase by lazy {
        (context.applicationContext as FaustApplication).database
    }
    private val preferenceManager: PreferenceManager by lazy {
        PreferenceManager(context)
    }
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val FREE_TIER_LAUNCH_PENALTY = 3
        private const val FREE_TIER_QUIT_PENALTY = 0 // Free 티어는 철회 시 차감 없음
    }

    /**
     * 앱 강행 실행 시 페널티 적용
     */
    fun applyLaunchPenalty(packageName: String, appName: String) {
        scope.launch {
            val userTier = preferenceManager.getUserTier()
            val penalty = when (userTier) {
                UserTier.FREE -> FREE_TIER_LAUNCH_PENALTY
                UserTier.STANDARD -> FREE_TIER_LAUNCH_PENALTY // MVP에서는 동일
                UserTier.FAUST_PRO -> FREE_TIER_LAUNCH_PENALTY // MVP에서는 동일
            }

            applyPenalty(penalty, "앱 강행 실행: $appName")
        }
    }

    /**
     * 앱 철회 시 페널티 적용 (Free 티어는 0)
     */
    fun applyQuitPenalty(packageName: String, appName: String) {
        scope.launch {
            val userTier = preferenceManager.getUserTier()
            val penalty = when (userTier) {
                UserTier.FREE -> FREE_TIER_QUIT_PENALTY
                UserTier.STANDARD -> 2 // MVP 이후 구현
                UserTier.FAUST_PRO -> 0 // MVP 이후 구현 (실제로는 50% 삭감)
            }

            if (penalty > 0) {
                applyPenalty(penalty, "앱 철회: $appName")
            }
        }
    }

    /**
     * 페널티를 적용합니다. DB 트랜잭션으로 포인트 차감과 거래 내역 저장을 원자적으로 처리합니다.
     */
    private suspend fun applyPenalty(penalty: Int, reason: String) {
        if (penalty <= 0) return

        database.withTransaction {
            // 현재 포인트 조회 (DB에서 계산)
            val currentPoints = database.pointTransactionDao().getTotalPoints() ?: 0
            val actualPenalty = penalty.coerceAtMost(currentPoints)

            if (actualPenalty > 0) {
                // 거래 내역 저장 (트랜잭션으로 원자적 처리)
                database.pointTransactionDao().insertTransaction(
                    com.faust.models.PointTransaction(
                        amount = -actualPenalty,
                        type = TransactionType.PENALTY,
                        reason = reason
                    )
                )
                
                // PreferenceManager 동기화 (호환성 유지)
                preferenceManager.setCurrentPoints((currentPoints - actualPenalty).coerceAtLeast(0))
            }
        }
    }
}
