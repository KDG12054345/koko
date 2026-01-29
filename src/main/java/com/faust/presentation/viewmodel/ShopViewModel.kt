package com.faust.presentation.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.faust.FaustApplication
import com.faust.data.database.FaustDatabase
import com.faust.data.utils.PreferenceManager
import com.faust.domain.ActivePassService
import com.faust.domain.FreePassService
import com.faust.models.FreePassItem
import com.faust.models.FreePassItemType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * ShopFragment의 데이터 관찰 및 비즈니스 로직을 담당하는 ViewModel입니다.
 */
class ShopViewModel(application: Application) : AndroidViewModel(application) {
    private val database: FaustDatabase = (application as FaustApplication).database
    private val preferenceManager: PreferenceManager = PreferenceManager(application)
    private val freePassService: FreePassService = FreePassService(application)
    private val activePassService: ActivePassService = ActivePassService(application)

    // 아이템 목록 StateFlow
    private val _items = MutableStateFlow<List<FreePassItemType>>(
        listOf(
            FreePassItemType.DOPAMINE_SHOT,
            FreePassItemType.STANDARD_TICKET,
            FreePassItemType.CINEMA_PASS
        )
    )
    val items: StateFlow<List<FreePassItemType>> = _items.asStateFlow()

    // 각 아이템의 보유 수량 StateFlow
    private val _itemQuantities = MutableStateFlow<Map<FreePassItemType, Int>>(emptyMap())
    val itemQuantities: StateFlow<Map<FreePassItemType, Int>> = _itemQuantities.asStateFlow()

    // 각 아이템의 쿨타임 StateFlow (밀리초)
    private val _itemCooldowns = MutableStateFlow<Map<FreePassItemType, Long>>(emptyMap())
    val itemCooldowns: StateFlow<Map<FreePassItemType, Long>> = _itemCooldowns.asStateFlow()

    // 현재 포인트 StateFlow
    private val _currentPoints = MutableStateFlow<Int>(0)
    val currentPoints: StateFlow<Int> = _currentPoints.asStateFlow()

    // 구매/사용 결과 StateFlow
    private val _purchaseResult = MutableStateFlow<FreePassService.PurchaseResult?>(null)
    val purchaseResult: StateFlow<FreePassService.PurchaseResult?> = _purchaseResult.asStateFlow()

    private val _useResult = MutableStateFlow<FreePassService.UseResult?>(null)
    val useResult: StateFlow<FreePassService.UseResult?> = _useResult.asStateFlow()

    init {
        observeItems()
        observePoints()
        observeCooldowns()
    }

    /**
     * 아이템 보유 수량을 관찰합니다.
     */
    private fun observeItems() {
        viewModelScope.launch {
            // 모든 아이템 타입에 대한 Flow를 combine
            combine(
                database.freePassItemDao().getItemFlow(FreePassItemType.DOPAMINE_SHOT),
                database.freePassItemDao().getItemFlow(FreePassItemType.STANDARD_TICKET),
                database.freePassItemDao().getItemFlow(FreePassItemType.CINEMA_PASS)
            ) { dopamineShot, standardTicket, cinemaPass ->
                mapOf(
                    FreePassItemType.DOPAMINE_SHOT to (dopamineShot?.quantity ?: 0),
                    FreePassItemType.STANDARD_TICKET to (standardTicket?.quantity ?: 0),
                    FreePassItemType.CINEMA_PASS to (cinemaPass?.quantity ?: 0)
                )
            }.collect { quantities ->
                _itemQuantities.value = quantities
            }
        }
    }

    /**
     * 현재 포인트를 관찰합니다.
     */
    private fun observePoints() {
        viewModelScope.launch {
            database.pointTransactionDao().getTotalPointsFlow().collect { points ->
                _currentPoints.value = points
            }
        }
    }

    /**
     * 쿨타임을 주기적으로 업데이트합니다.
     */
    private fun observeCooldowns() {
        viewModelScope.launch {
            while (true) {
                val cooldowns = mutableMapOf<FreePassItemType, Long>()
                _items.value.forEach { itemType ->
                    cooldowns[itemType] = freePassService.getRemainingCooldown(itemType)
                }
                _itemCooldowns.value = cooldowns
                kotlinx.coroutines.delay(1000) // 1초마다 업데이트
            }
        }
    }

    /**
     * 아이템을 구매합니다.
     */
    fun purchaseItem(itemType: FreePassItemType) {
        viewModelScope.launch {
            val result = freePassService.purchaseItem(itemType)
            _purchaseResult.value = result

            // 도파민 샷은 구매와 동시에 사용 및 활성화
            if (itemType == FreePassItemType.DOPAMINE_SHOT && result is FreePassService.PurchaseResult.Success) {
                // 도파민 샷은 구매 시 이미 사용 처리됨 (FreePassService에서 lastUseTime 설정)
                // 활성 패스 활성화
                activePassService.activatePass(itemType)
            }
        }
    }

    /**
     * 아이템을 사용합니다.
     */
    fun useItem(itemType: FreePassItemType) {
        viewModelScope.launch {
            val result = freePassService.useItem(itemType)
            _useResult.value = result

            // 사용 성공 시 활성 패스 활성화
            if (result is FreePassService.UseResult.Success) {
                activePassService.activatePass(itemType)
            }
        }
    }

    /**
     * 아이템 가격을 계산합니다.
     */
    fun getItemPrice(itemType: FreePassItemType): Int {
        val currentQuantity = _itemQuantities.value[itemType] ?: 0
        return freePassService.calculateProgressivePrice(itemType, currentQuantity)
    }

    /**
     * 아이템 구매 가능 여부를 확인합니다.
     */
    suspend fun canPurchaseItem(itemType: FreePassItemType): Boolean {
        return freePassService.canPurchase(itemType)
    }

    /**
     * 구매 결과를 초기화합니다.
     */
    fun clearPurchaseResult() {
        _purchaseResult.value = null
    }

    /**
     * 사용 결과를 초기화합니다.
     */
    fun clearUseResult() {
        _useResult.value = null
    }
}
