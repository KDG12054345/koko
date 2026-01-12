package com.faust.services

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.faust.FaustApplication
import com.faust.MainActivity
import com.faust.R
import com.faust.database.FaustDatabase
import com.faust.ui.GuiltyNegotiationOverlay
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

class AppBlockingService : LifecycleService() {
    private val database: FaustDatabase by lazy {
        (application as FaustApplication).database
    }
    private var monitoringJob: Job? = null
    private var blockedAppsFlowJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var currentOverlay: GuiltyNegotiationOverlay? = null
    
    // 차단된 앱 목록을 메모리에 캐싱 (스레드 안전)
    private val blockedAppsCache = ConcurrentHashMap.newKeySet<String>()

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "app_blocking_channel"
        private const val CHECK_INTERVAL_MS = 1000L // 1초마다 체크
        private const val DELAY_BEFORE_OVERLAY_MS = 4000L..6000L // 4-6초 지연

        fun startService(context: Context) {
            val intent = Intent(context, AppBlockingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, AppBlockingService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        initializeBlockedAppsCache()
        startMonitoring()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        monitoringJob?.cancel()
        blockedAppsFlowJob?.cancel()
        serviceScope.cancel()
        hideOverlay()
        blockedAppsCache.clear()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    /**
     * 차단된 앱 목록을 초기 로드하고 Flow를 구독하여 변경사항을 실시간으로 감지
     */
    private fun initializeBlockedAppsCache() {
        blockedAppsFlowJob?.cancel()
        blockedAppsFlowJob = serviceScope.launch {
            try {
                // 초기 로드
                val initialApps = database.appBlockDao().getAllBlockedApps().first()
                blockedAppsCache.clear()
                blockedAppsCache.addAll(initialApps.map { it.packageName })
                
                // Flow를 구독하여 변경사항 실시간 감지
                database.appBlockDao().getAllBlockedApps().collect { apps ->
                    blockedAppsCache.clear()
                    blockedAppsCache.addAll(apps.map { it.packageName })
                }
            } catch (e: Exception) {
                // 에러 발생 시 빈 캐시로 시작
                blockedAppsCache.clear()
            }
        }
    }

    private fun startMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = serviceScope.launch {
            var lastCheckedApp: String? = null
            var overlayDelayJob: Job? = null

            while (isActive) {
                try {
                    val currentApp = getCurrentForegroundApp()
                    
                    if (currentApp != null && currentApp != lastCheckedApp) {
                        // 앱이 변경되었을 때
                        overlayDelayJob?.cancel()
                        
                        // 메모리 캐시에서 조회 (DB 조회 제거 - 배터리 절약)
                        val isBlocked = blockedAppsCache.contains(currentApp)
                        
                        if (isBlocked) {
                            // 차단된 앱 감지 - 4-6초 지연 후 오버레이 표시
                            overlayDelayJob = launch {
                                val delay = DELAY_BEFORE_OVERLAY_MS.random()
                                delay(delay)
                                
                                if (isActive) {
                                    val appName = getAppName(currentApp)
                                    showOverlay(currentApp, appName)
                                }
                            }
                        } else {
                            // 차단되지 않은 앱이면 오버레이 숨김
                            hideOverlay()
                        }
                        
                        lastCheckedApp = currentApp
                    } else if (currentApp == null || currentApp != lastCheckedApp) {
                        // 포그라운드 앱이 없거나 다른 앱으로 변경된 경우
                        hideOverlay()
                        lastCheckedApp = currentApp
                    }
                    
                    delay(CHECK_INTERVAL_MS)
                } catch (e: Exception) {
                    // 에러 발생 시 계속 진행
                }
            }
        }
    }

    private fun showOverlay(packageName: String, appName: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            if (currentOverlay == null) {
                currentOverlay = GuiltyNegotiationOverlay(this@AppBlockingService).apply {
                    show(packageName, appName)
                }
            }
        }
    }

    private fun hideOverlay() {
        lifecycleScope.launch(Dispatchers.Main) {
            currentOverlay?.dismiss()
            currentOverlay = null
        }
    }

    private fun getCurrentForegroundApp(): String? {
        return try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
            val time = System.currentTimeMillis()
            val stats = usageStatsManager.queryUsageStats(
                android.app.usage.UsageStatsManager.INTERVAL_DAILY,
                time - 1000,
                time
            )
            
            if (stats.isNullOrEmpty()) {
                null
            } else {
                stats.maxByOrNull { it.lastTimeUsed }?.packageName
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getAppName(packageName: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_app_blocking),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "앱 차단 서비스"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_app_blocking_title))
            .setContentText("앱 차단 모니터링 중...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
