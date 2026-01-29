package com.faust.domain

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.room.withTransaction
import com.faust.FaustApplication
import com.faust.data.database.FaustDatabase
import com.faust.data.utils.PreferenceManager
import com.faust.data.utils.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * [시스템 진입점: 시간 기반 진입점 / 부팅 진입점]
 * 
 * 역할: AlarmManager에 의해 매일 사용자 지정 시간에 시스템이 브로드캐스트를 던져 일일 초기화 로직을 실행시키는 지점입니다.
 * 또한 기기 재부팅 시 ACTION_BOOT_COMPLETED 이벤트를 수신하여 중단된 알람을 재등록하는 지점입니다.
 * 트리거: AlarmManager 트리거 (사용자 지정 시간) 또는 ACTION_BOOT_COMPLETED 브로드캐스트
 * 처리: 일일 초기화 로직 실행 또는 알람 재등록
 * 
 * @see ARCHITECTURE.md#시스템-진입점-system-entry-points
 */
class DailyResetReceiver : BroadcastReceiver() {
    /**
     * [시스템 진입점: 시간 기반 진입점 / 부팅 진입점]
     * 
     * 역할: AlarmManager 트리거 또는 부팅 완료 이벤트를 수신하여 일일 초기화 로직을 실행합니다.
     * 트리거: "com.faust.DAILY_RESET" 액션 또는 ACTION_BOOT_COMPLETED
     * 처리: DailyResetService.performReset() 호출
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "com.faust.DAILY_RESET") {
            DailyResetService.performReset(context)
        }
    }
}

object DailyResetService {
    private const val TAG = "DailyResetService"
    private const val REQUEST_CODE = 1004

    /**
     * [시스템 진입점: 시간 기반 진입점]
     * 
     * 역할: AlarmManager에 일일 초기화 알람을 등록합니다. 매일 사용자 지정 시간에 DailyResetReceiver를 트리거합니다.
     * 트리거: MainActivity.onCreate() 또는 performReset() 완료 후 또는 사용자 지정 시간 변경 시
     * 처리: AlarmManager에 다음 사용자 지정 시간 알람 등록
     */
    fun scheduleDailyReset(context: Context): Long {
        try {
            val preferenceManager = PreferenceManager(context)
            val customTime = preferenceManager.getCustomDailyResetTime()
            val nextResetTime = getNextResetTime(customTime)

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, DailyResetReceiver::class.java).apply {
                action = "com.faust.DAILY_RESET"
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12 (API 31) 이상: 정확한 알람 권한 확인
                if (alarmManager.canScheduleExactAlarms()) {
                    try {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            nextResetTime,
                            pendingIntent
                        )
                        Log.d(TAG, "Exact alarm scheduled for daily reset at $customTime")
                    } catch (e: SecurityException) {
                        Log.e(TAG, "SecurityException: SCHEDULE_EXACT_ALARM permission not granted", e)
                        // 비정확 알람으로 폴백
                        try {
                            alarmManager.set(
                                AlarmManager.RTC_WAKEUP,
                                nextResetTime,
                                pendingIntent
                            )
                            Log.w(TAG, "Fallback to inexact alarm for daily reset")
                        } catch (fallbackException: Exception) {
                            Log.e(TAG, "Failed to schedule daily reset alarm", fallbackException)
                        }
                    }
                } else {
                    // 권한이 없으면 비정확 알람 사용
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        nextResetTime,
                        pendingIntent
                    )
                    Log.w(TAG, "Using inexact alarm for daily reset (exact alarm permission not granted)")
                }
            } else {
                // Android 11 이하: setExact 사용
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        nextResetTime,
                        pendingIntent
                    )
                } else {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP,
                        nextResetTime,
                        pendingIntent
                    )
                }
                Log.d(TAG, "Exact alarm scheduled for daily reset at $customTime (API < 31)")
            }

            return nextResetTime
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule daily reset", e)
            return 0L
        }
    }

    /**
     * 사용자 지정 시간 기준으로 다음 리셋 시간을 계산합니다.
     * 
     * @param customTime "HH:mm" 형식의 사용자 지정 시간 (예: "02:00")
     * @return 다음 리셋 시간의 timestamp (밀리초)
     */
    fun getNextResetTime(customTime: String): Long {
        return TimeUtils.getNextResetTime(customTime)
    }

    /**
     * [시스템 진입점: 시간 기반 진입점]
     * 
     * 역할: 일일 초기화 로직을 실행합니다. 스탠다드 티켓의 일일 사용 횟수를 0으로 초기화합니다.
     * 트리거: AlarmManager 트리거 (사용자 지정 시간)
     * 처리: DailyUsageRecord의 standardTicketUsedCount를 0으로 초기화
     */
    fun performReset(context: Context) {
        val scope = CoroutineScope(Dispatchers.IO)
        scope.launch {
            try {
                val database = (context.applicationContext as FaustApplication).database
                val preferenceManager = PreferenceManager(context)

                database.withTransaction {
                    try {
                        // 사용자 지정 시간 기준 오늘 날짜 계산
                        val customTime = preferenceManager.getCustomDailyResetTime()
                        val today = TimeUtils.getDayString(customTime)

                        // 오늘 날짜의 기록이 있으면 사용 횟수 초기화, 없으면 생성
                        val todayRecord = database.dailyUsageRecordDao().getTodayRecord(today)
                        if (todayRecord != null) {
                            // 기존 기록의 사용 횟수만 0으로 초기화
                            val resetRecord = todayRecord.copy(standardTicketUsedCount = 0)
                            database.dailyUsageRecordDao().insertOrUpdateRecord(resetRecord)
                            Log.d(TAG, "일일 사용 횟수 초기화 완료: $today")
                        } else {
                            // 새 기록 생성 (이미 0이지만 명시적으로 생성)
                            val newRecord = com.faust.models.DailyUsageRecord(
                                date = today,
                                standardTicketUsedCount = 0
                            )
                            database.dailyUsageRecordDao().insertOrUpdateRecord(newRecord)
                            Log.d(TAG, "새 일일 기록 생성: $today")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error performing daily reset in transaction", e)
                        throw e // 트랜잭션 롤백을 위해 예외 재발생
                    }
                }

                // 다음 일일 초기화 스케줄링
                val appContext = context.applicationContext
                if (appContext != null) {
                    scheduleDailyReset(appContext)
                } else {
                    Log.e(TAG, "Cannot schedule next daily reset: applicationContext is null")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to perform daily reset", e)
                // 트랜잭션이 실패하면 자동으로 롤백됨
            }
        }
    }

    /**
     * 부팅 완료 시 일일 초기화를 다시 스케줄링합니다.
     */
    fun rescheduleOnBoot(context: Context) {
        scheduleDailyReset(context)
    }
}
