package com.faust.domain

import android.content.Context
import android.util.Log
import androidx.work.*
import com.faust.FaustApplication
import com.faust.data.database.FaustDatabase
import com.faust.data.utils.PreferenceManager
import com.faust.models.FreePassItemType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * 활성 프리 패스 추적 및 타이머 관리 서비스
 * WorkManager를 사용하여 앱이 종료되어도 타이머가 동작하도록 합니다.
 */
class ActivePassService(private val context: Context) {
    private val database: FaustDatabase by lazy {
        (context.applicationContext as FaustApplication).database
    }
    private val preferenceManager: PreferenceManager by lazy {
        PreferenceManager(context)
    }

    companion object {
        private const val TAG = "ActivePassService"
        private const val WORK_NAME_PREFIX = "active_pass_expire_"

        // 아이템별 지속 시간 (밀리초)
        private const val DOPAMINE_SHOT_DURATION_MS = 20 * 60 * 1000L  // 20분
        private const val STANDARD_TICKET_DURATION_MS = 60 * 60 * 1000L  // 1시간
        private const val CINEMA_PASS_DURATION_MS = 4 * 60 * 60 * 1000L  // 4시간
    }

    /**
     * 활성 패스 정보
     */
    data class ActivePass(
        val itemType: FreePassItemType,
        val startTime: Long,
        val expireTime: Long
    )

    /**
     * 현재 활성화된 패스를 가져옵니다.
     */
    suspend fun getActivePass(): ActivePass? {
        return withContext(Dispatchers.IO) {
            try {
                val activeItemType = preferenceManager.getActivePassItemType()
                    ?: return@withContext null
                val startTime = preferenceManager.getActivePassStartTime()
                if (startTime <= 0) return@withContext null

                val duration = getPassDuration(activeItemType)
                val expireTime = startTime + duration

                // 만료 시간이 지났으면 null 반환
                if (System.currentTimeMillis() >= expireTime) {
                    // 만료된 패스 정리
                    clearActivePass()
                    return@withContext null
                }

                ActivePass(
                    itemType = activeItemType,
                    startTime = startTime,
                    expireTime = expireTime
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error getting active pass", e)
                null
            }
        }
    }

    /**
     * 패스를 활성화합니다.
     * 
     * @param itemType 활성화할 아이템 타입
     * @return 활성화 성공 여부
     */
    suspend fun activatePass(itemType: FreePassItemType): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val duration = getPassDuration(itemType)
                val expireTime = now + duration

                // 활성 패스 정보 저장
                preferenceManager.setActivePassItemType(itemType)
                preferenceManager.setActivePassStartTime(now)

                // WorkManager로 만료 시간 스케줄링
                scheduleExpirationWork(itemType, expireTime)

                Log.d(TAG, "패스 활성화: $itemType, 만료 시간: $expireTime")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error activating pass: $itemType", e)
                false
            }
        }
    }

    /**
     * 활성 패스를 해제합니다.
     */
    suspend fun deactivatePass() {
        withContext(Dispatchers.IO) {
            try {
                clearActivePass()
                Log.d(TAG, "패스 해제 완료")
            } catch (e: Exception) {
                Log.e(TAG, "Error deactivating pass", e)
            }
        }
    }

    /**
     * 특정 앱 그룹에 대해 패스가 활성화되어 있는지 확인합니다.
     */
    suspend fun isPassActiveForGroup(groupType: com.faust.models.AppGroupType): Boolean {
        return withContext(Dispatchers.IO) {
            val activePass = getActivePass() ?: return@withContext false
            when (groupType) {
                com.faust.models.AppGroupType.SNS -> activePass.itemType == FreePassItemType.DOPAMINE_SHOT
                com.faust.models.AppGroupType.OTT -> activePass.itemType == FreePassItemType.CINEMA_PASS
            }
        }
    }

    /**
     * 전체 앱에 대해 패스가 활성화되어 있는지 확인합니다 (스탠다드 티켓).
     */
    suspend fun isStandardTicketActive(): Boolean {
        return withContext(Dispatchers.IO) {
            val activePass = getActivePass()
            activePass?.itemType == FreePassItemType.STANDARD_TICKET
        }
    }

    /**
     * 패스 지속 시간을 가져옵니다.
     */
    private fun getPassDuration(itemType: FreePassItemType): Long {
        return when (itemType) {
            FreePassItemType.DOPAMINE_SHOT -> DOPAMINE_SHOT_DURATION_MS
            FreePassItemType.STANDARD_TICKET -> STANDARD_TICKET_DURATION_MS
            FreePassItemType.CINEMA_PASS -> CINEMA_PASS_DURATION_MS
        }
    }

    /**
     * 만료 시간 WorkManager 스케줄링
     */
    private suspend fun scheduleExpirationWork(itemType: FreePassItemType, expireTime: Long) {
        val delay = expireTime - System.currentTimeMillis()
        if (delay <= 0) {
            // 이미 만료된 경우 즉시 실행
            expirePass(itemType)
            return
        }

        val workName = "${WORK_NAME_PREFIX}${itemType.name}"
        val workRequest = OneTimeWorkRequestBuilder<PassExpirationWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    "itemType" to itemType.name
                )
            )
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName, ExistingWorkPolicy.REPLACE, workRequest)

        Log.d(TAG, "만료 WorkManager 스케줄링: $itemType, ${delay / 1000}초 후")
    }

    /**
     * 활성 패스를 정리합니다.
     */
    private suspend fun clearActivePass() {
        preferenceManager.setActivePassItemType(null)
        preferenceManager.setActivePassStartTime(0)

        // 모든 만료 Work 취소
        WorkManager.getInstance(context)
            .cancelUniqueWork(WORK_NAME_PREFIX)
    }

    /**
     * 패스를 만료시킵니다.
     */
    private suspend fun expirePass(itemType: FreePassItemType) {
        Log.d(TAG, "패스 만료: $itemType")
        clearActivePass()
        // AppBlockingService에 만료 알림 (필요 시)
    }
}

/**
 * 패스 만료를 처리하는 Worker
 */
class PassExpirationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            val itemTypeName = inputData.getString("itemType")
            if (itemTypeName != null) {
                val itemType = FreePassItemType.valueOf(itemTypeName)
                val activePassService = ActivePassService(applicationContext)
                activePassService.deactivatePass()
                Log.d("PassExpirationWorker", "패스 만료 처리 완료: $itemType")
            }
            Result.success()
        } catch (e: Exception) {
            Log.e("PassExpirationWorker", "패스 만료 처리 실패", e)
            Result.retry()
        }
    }
}
