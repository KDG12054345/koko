package com.faust.services

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.faust.FaustApplication
import com.faust.database.FaustDatabase
import com.faust.ui.GuiltyNegotiationOverlay
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

class AppBlockingService : AccessibilityService(), LifecycleOwner {
    private val database: FaustDatabase by lazy {
        (application as FaustApplication).database
    }
    private var blockedAppsFlowJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var currentOverlay: GuiltyNegotiationOverlay? = null
    private var overlayDelayJob: Job? = null
    
    // 차단된 앱 목록을 메모리에 캐싱 (스레드 안전)
    private val blockedAppsCache = ConcurrentHashMap.newKeySet<String>()
    
    // LifecycleOwner 구현
    private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)
    
    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    companion object {
        private val DELAY_BEFORE_OVERLAY_MS = 4000L..6000L // 4-6초 지연

        /**
         * 접근성 서비스가 활성화되어 있는지 확인
         */
        fun isServiceEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            
            val serviceName = ComponentName(
                context.packageName,
                AppBlockingService::class.java.name
            ).flattenToString()
            
            return enabledServices.contains(serviceName)
        }

        /**
         * 접근성 서비스 설정 화면으로 이동
         */
        fun requestAccessibilityPermission(context: Context) {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(intent)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        
        initializeBlockedAppsCache()
    }

    override fun onDestroy() {
        super.onDestroy()
        blockedAppsFlowJob?.cancel()
        overlayDelayJob?.cancel()
        serviceScope.cancel()
        hideOverlay()
        blockedAppsCache.clear()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
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

    /**
     * 접근성 이벤트 처리 - 앱 실행 감지
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString()
            
            if (packageName != null) {
                handleAppLaunch(packageName)
            }
        }
    }

    /**
     * 앱 실행 이벤트 처리
     */
    private fun handleAppLaunch(packageName: String) {
        // 메모리 캐시에서 차단 여부 확인
        val isBlocked = blockedAppsCache.contains(packageName)
        
        if (isBlocked) {
            // 이전 지연 작업 취소
            overlayDelayJob?.cancel()
            
            // 차단된 앱 감지 - 4-6초 지연 후 오버레이 표시
            overlayDelayJob = serviceScope.launch {
                val delay = DELAY_BEFORE_OVERLAY_MS.random()
                delay(delay)
                
                if (isActive) {
                    val appName = getAppName(packageName)
                    showOverlay(packageName, appName)
                }
            }
        } else {
            // 차단되지 않은 앱이면 오버레이 숨김
            hideOverlay()
        }
    }

    /**
     * 접근성 서비스 인터럽트 처리
     */
    override fun onInterrupt() {
        // 접근성 서비스가 중단될 때 호출
        hideOverlay()
    }

    private fun showOverlay(packageName: String, appName: String) {
        serviceScope.launch(Dispatchers.Main) {
            if (currentOverlay == null) {
                currentOverlay = GuiltyNegotiationOverlay(this@AppBlockingService).apply {
                    show(packageName, appName)
                }
            }
        }
    }

    private fun hideOverlay() {
        serviceScope.launch(Dispatchers.Main) {
            currentOverlay?.dismiss()
            currentOverlay = null
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
}
