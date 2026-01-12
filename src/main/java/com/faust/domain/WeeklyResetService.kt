package com.faust.domain

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.room.withTransaction
import com.faust.FaustApplication
import com.faust.data.database.FaustDatabase
import com.faust.data.utils.PreferenceManager
import com.faust.data.utils.TimeUtils
import com.faust.models.TransactionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * [시스템 진입점: 시간 기반 진입점 / 부팅 진입점]
 * 
 * 역할: AlarmManager에 의해 매주 월요일 00:00에 시스템이 브로드캐스트를 던져 정산 로직을 실행시키는 지점입니다.
 * 또한 기기 재부팅 시 ACTION_BOOT_COMPLETED 이벤트를 수신하여 중단된 서비스와 알람을 재등록하는 지점입니다.
 * 트리거: AlarmManager 트리거 (월요일 00:00) 또는 ACTION_BOOT_COMPLETED 브로드캐스트
 * 처리: 주간 정산 로직 실행 또는 알람 재등록
 * 
 * @see ARCHITECTURE.md#시스템-진입점-system-entry-points
 */
class WeeklyResetReceiver : BroadcastReceiver() {
    /**
     * [시스템 진입점: 시간 기반 진입점 / 부팅 진입점]
     * 
     * 역할: AlarmManager 트리거 또는 부팅 완료 이벤트를 수신하여 정산 로직을 실행합니다.
     * 트리거: "com.faust.WEEKLY_RESET" 액션 또는 ACTION_BOOT_COMPLETED
     * 처리: WeeklyResetService.performReset() 호출
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "com.faust.WEEKLY_RESET") {
            WeeklyResetService.performReset(context)
        }
    }
}

object WeeklyResetService {
    private const val TAG = "WeeklyResetService"
    private const val REQUEST_CODE = 1003
    private const val RESET_THRESHOLD = 100

    /**
     * [시스템 진입점: 시간 기반 진입점]
     * 
     * 역할: AlarmManager에 주간 정산 알람을 등록합니다. 매주 월요일 00:00에 WeeklyResetReceiver를 트리거합니다.
     * 트리거: MainActivity.onCreate() 또는 performReset() 완료 후
     * 처리: AlarmManager에 다음 월요일 00:00 알람 등록
     */
    fun scheduleWeeklyReset(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, WeeklyResetReceiver::class.java).apply {
            action = "com.faust.WEEKLY_RESET"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nextMondayMidnight = TimeUtils.getNextMondayMidnight()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextMondayMidnight,
                pendingIntent
            )
        } else {
            @Suppress("DEPRECATION")
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                nextMondayMidnight,
                pendingIntent
            )
        }
    }

    /**
     * [시스템 진입점: 시간 기반 진입점]
     * 
     * 역할: 주간 정산 로직을 실행합니다. DB 트랜잭션으로 포인트 조정과 거래 내역 저장을 원자적으로 처리합니다.
     * 트리거: AlarmManager가 매주 월요일 00:00에 WeeklyResetReceiver를 통해 호출
     * 처리: 포인트 몰수 처리 (100 WP 초과 시 초과분 몰수, 이하 시 전액 몰수), 다음 주 정산 스케줄링
     */
    fun performReset(context: Context) {
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                val database = (context.applicationContext as FaustApplication).database
                val preferenceManager = PreferenceManager(context)

                database.withTransaction {
                    try {
                        // 현재 포인트 조회 (DB에서 계산)
                        val currentPoints = database.pointTransactionDao().getTotalPoints() ?: 0

                        if (currentPoints > RESET_THRESHOLD) {
                            // 100 WP를 제외한 모든 포인트 몰수
                            val pointsToRemove = currentPoints - RESET_THRESHOLD
                            
                            // 거래 내역 저장 (트랜잭션으로 원자적 처리)
                            database.pointTransactionDao().insertTransaction(
                                com.faust.models.PointTransaction(
                                    amount = -pointsToRemove,
                                    type = TransactionType.RESET,
                                    reason = "주간 정산: 100 WP 제외 몰수"
                                )
                            )
                            
                            // PreferenceManager 동기화
                            preferenceManager.setCurrentPoints(RESET_THRESHOLD)
                        } else {
                            // 100 WP 이하면 모두 몰수
                            if (currentPoints > 0) {
                                // 거래 내역 저장 (트랜잭션으로 원자적 처리)
                                database.pointTransactionDao().insertTransaction(
                                    com.faust.models.PointTransaction(
                                        amount = -currentPoints,
                                        type = TransactionType.RESET,
                                        reason = "주간 정산: 전액 몰수"
                                    )
                                )
                            }
                            
                            // PreferenceManager 동기화
                            preferenceManager.setCurrentPoints(0)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error performing reset in transaction", e)
                        throw e // 트랜잭션 롤백을 위해 예외 재발생
                    }
                }

                // 마지막 리셋 시간 업데이트 (트랜잭션 외부에서 실행)
                preferenceManager.setLastResetTime(System.currentTimeMillis())

                // 다음 주간 정산 스케줄링
                scheduleWeeklyReset(context)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to perform weekly reset", e)
                // 트랜잭션이 실패하면 자동으로 롤백됨
                // 다음 주간 정산은 스케줄링하지 않음 (다음 시도 시 재시도)
            }
        }
    }

    /**
     * 부팅 완료 시 주간 정산을 다시 스케줄링합니다.
     */
    fun rescheduleOnBoot(context: Context) {
        scheduleWeeklyReset(context)
    }
}
