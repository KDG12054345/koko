package com.faust.services

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.room.withTransaction
import com.faust.FaustApplication
import com.faust.database.FaustDatabase
import com.faust.models.TransactionType
import com.faust.utils.PreferenceManager
import com.faust.utils.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WeeklyResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AlarmManager.ACTION_BOOT_COMPLETED ||
            intent.action == "com.faust.WEEKLY_RESET") {
            WeeklyResetService.performReset(context)
        }
    }
}

object WeeklyResetService {
    private const val REQUEST_CODE = 1003
    private const val RESET_THRESHOLD = 100

    /**
     * 주간 정산 알람을 설정합니다.
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
     * 주간 정산을 수행합니다. DB 트랜잭션으로 포인트 조정과 거래 내역 저장을 원자적으로 처리합니다.
     */
    fun performReset(context: Context) {
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            val database = (context.applicationContext as FaustApplication).database
            val preferenceManager = PreferenceManager(context)

            database.withTransaction {
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
            }

            // 마지막 리셋 시간 업데이트
            preferenceManager.setLastResetTime(System.currentTimeMillis())

            // 다음 주간 정산 스케줄링
            scheduleWeeklyReset(context)
        }
    }

    /**
     * 부팅 완료 시 주간 정산을 다시 스케줄링합니다.
     */
    fun rescheduleOnBoot(context: Context) {
        scheduleWeeklyReset(context)
    }
}
