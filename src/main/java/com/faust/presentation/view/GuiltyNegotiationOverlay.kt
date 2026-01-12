package com.faust.presentation.view

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.widget.Button
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.faust.R
import com.faust.domain.PenaltyService
import kotlinx.coroutines.*

/**
 * [핵심 이벤트: 차단 관련 이벤트 - showOverlay]
 * 
 * 역할: 차단 대상 앱임이 확인되면 4~6초의 지연 후 화면 최상단에 표시되는 오버레이입니다.
 * 트리거: AppBlockingService.showOverlay() 호출
 * 처리: WindowManager를 통해 시스템 레벨 오버레이 표시, 30초 카운트다운 시작, 강행/철회 버튼 제공
 * 
 * @see ARCHITECTURE.md#핵심-이벤트-정의-core-event-definitions
 */
class GuiltyNegotiationOverlay(
    private val context: Context
) : LifecycleOwner {
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(this)
    private var countdownJob: Job? = null
    private val penaltyService = PenaltyService(context)
    private var packageName: String = ""
    private var appName: String = ""
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        lifecycleRegistry.currentState = Lifecycle.State.INITIALIZED
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    /**
     * [핵심 이벤트: 차단 관련 이벤트 - showOverlay]
     * 
     * 역할: 오버레이를 화면에 표시합니다.
     * 트리거: AppBlockingService.showOverlay() 호출
     * 처리: WindowManager를 통해 시스템 레벨 오버레이 추가, 30초 카운트다운 시작
     */
    fun show(packageName: String, appName: String) {
        this.packageName = packageName
        this.appName = appName

        if (overlayView != null) {
            return // 이미 표시 중
        }

        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return
        this.windowManager = windowManager

        overlayView = createOverlayView()
        overlayView?.let { view ->
            val params = createWindowParams()
            try {
                windowManager.addView(view, params)
                startCountdown()
            } catch (e: Exception) {
                // 오버레이 추가 실패
                overlayView = null
            }
        }
    }

    fun dismiss() {
        countdownJob?.cancel()
        coroutineScope.cancel()
        overlayView?.let { view ->
            windowManager?.removeView(view)
        }
        overlayView = null
        windowManager = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    private fun createOverlayView(): View {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.overlay_guilty_negotiation, null)

        val titleText = view.findViewById<TextView>(R.id.textTitle)
        val messageText = view.findViewById<TextView>(R.id.textMessage)
        val countdownText = view.findViewById<TextView>(R.id.textCountdown)
        val proceedButton = view.findViewById<Button>(R.id.buttonProceed)
        val cancelButton = view.findViewById<Button>(R.id.buttonCancel)

        titleText.text = context.getString(R.string.guilty_negotiation_title)
        messageText.text = context.getString(R.string.guilty_negotiation_message)

        proceedButton.setOnClickListener {
            onProceed()
        }

        cancelButton.setOnClickListener {
            onCancel()
        }

        // 30초 카운트다운 시작
        startCountdown(countdownText)

        return view
    }

    private fun startCountdown(countdownText: TextView? = null) {
        countdownJob?.cancel()
        countdownJob = coroutineScope.launch {
            var remainingSeconds = 30
            val textView = countdownText ?: overlayView?.findViewById(R.id.textCountdown)

            while (remainingSeconds > 0 && isActive) {
                textView?.text = context.getString(R.string.wait_time, remainingSeconds)
                delay(1000)
                remainingSeconds--
            }

            if (isActive && remainingSeconds == 0) {
                // 30초 경과 후에도 버튼 활성화
                textView?.text = context.getString(R.string.wait_time, 0)
            }
        }
    }

    /**
     * [핵심 이벤트: 포인트 및 페널티 이벤트 - onProceed]
     * 
     * 역할: 사용자가 오버레이에서 '강행'을 선택할 때 발생하며, PenaltyService를 통해 3 WP를 차감하고 오버레이를 닫습니다.
     * 트리거: 사용자가 오버레이의 '강행' 버튼 클릭
     * 처리: PenaltyService.applyLaunchPenalty() 호출 (Free 티어: 3 WP 차감), 오버레이 닫기
     * 
     * @see ARCHITECTURE.md#핵심-이벤트-정의-core-event-definitions
     */
    private fun onProceed() {
        // 강행 실행 - 페널티 적용
        penaltyService.applyLaunchPenalty(packageName, appName)
        dismiss()
    }

    /**
     * [핵심 이벤트: 포인트 및 페널티 이벤트 - onCancel]
     * 
     * 역할: 사용자가 '철회'를 선택할 때 발생하며, 오버레이를 닫고 해당 앱 사용을 중단하도록 유도합니다 (Free 티어는 페널티 0).
     * 트리거: 사용자가 오버레이의 '철회' 버튼 클릭
     * 처리: PenaltyService.applyQuitPenalty() 호출 (Free 티어: 페널티 0), 오버레이 닫기
     * 
     * @see ARCHITECTURE.md#핵심-이벤트-정의-core-event-definitions
     */
    private fun onCancel() {
        // 철회 - Free 티어는 페널티 없음
        penaltyService.applyQuitPenalty(packageName, appName)
        dismiss()
    }

    private fun createWindowParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }
    }
}
