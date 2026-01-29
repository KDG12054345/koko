package com.faust.presentation.view

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.faust.R
import com.faust.domain.FreePassService
import com.faust.models.FreePassItemType
import com.faust.presentation.viewmodel.ShopViewModel
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * 상점 Fragment
 * 프리 패스 아이템을 구매하고 사용할 수 있는 UI를 제공합니다.
 */
class ShopFragment : Fragment() {
    private val viewModel: ShopViewModel by viewModels()
    private lateinit var itemsRecyclerView: RecyclerView
    private lateinit var inventoryRecyclerView: RecyclerView
    private lateinit var itemsAdapter: ShopItemAdapter
    private lateinit var inventoryAdapter: InventoryAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_shop, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        itemsRecyclerView = view.findViewById(R.id.recyclerViewItems)
        inventoryRecyclerView = view.findViewById(R.id.recyclerViewInventory)

        setupItemsRecyclerView()
        setupInventoryRecyclerView()
        observeViewModel()
    }

    private fun setupItemsRecyclerView() {
        itemsAdapter = ShopItemAdapter(
            viewModel = viewModel,
            onPurchaseClick = { itemType ->
                viewModel.purchaseItem(itemType)
            }
        )
        itemsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        itemsRecyclerView.adapter = itemsAdapter

        lifecycleScope.launch {
            viewModel.items.collect { items ->
                itemsAdapter.submitList(items)
            }
        }
    }

    private fun setupInventoryRecyclerView() {
        inventoryAdapter = InventoryAdapter(
            viewModel = viewModel,
            onUseClick = { itemType ->
                viewModel.useItem(itemType)
            }
        )
        inventoryRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        inventoryRecyclerView.adapter = inventoryAdapter

        lifecycleScope.launch {
            viewModel.itemQuantities.collect { quantities ->
                val inventoryItems = quantities.filter { (_, quantity) -> quantity > 0 }
                    .map { (itemType, _) -> itemType }
                inventoryAdapter.submitList(inventoryItems)
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            // 구매 결과 관찰
            viewModel.purchaseResult.collect { result ->
                result?.let {
                    when (it) {
                        is FreePassService.PurchaseResult.Success -> {
                            Toast.makeText(requireContext(), getString(R.string.purchase_success), Toast.LENGTH_SHORT).show()
                        }
                        is FreePassService.PurchaseResult.Failure -> {
                            Toast.makeText(requireContext(), getString(R.string.purchase_failed, it.reason), Toast.LENGTH_SHORT).show()
                        }
                    }
                    viewModel.clearPurchaseResult()
                }
            }
        }

        lifecycleScope.launch {
            // 사용 결과 관찰
            viewModel.useResult.collect { result ->
                result?.let {
                    when (it) {
                        is FreePassService.UseResult.Success -> {
                            Toast.makeText(requireContext(), getString(R.string.use_success), Toast.LENGTH_SHORT).show()
                        }
                        is FreePassService.UseResult.Failure -> {
                            Toast.makeText(requireContext(), getString(R.string.use_failed, it.reason), Toast.LENGTH_SHORT).show()
                        }
                    }
                    viewModel.clearUseResult()
                }
            }
        }
    }
}

/**
 * 상점 아이템 어댑터
 */
class ShopItemAdapter(
    private val viewModel: ShopViewModel,
    private val onPurchaseClick: (FreePassItemType) -> Unit
) : RecyclerView.Adapter<ShopItemAdapter.ViewHolder>() {
    private val items = mutableListOf<FreePassItemType>()

    fun submitList(newItems: List<FreePassItemType>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_shop_item, parent, false)
        return ViewHolder(view, viewModel, onPurchaseClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(
        itemView: View,
        private val viewModel: ShopViewModel,
        private val onPurchaseClick: (FreePassItemType) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val itemName: TextView = itemView.findViewById(R.id.textItemName)
        private val itemDescription: TextView = itemView.findViewById(R.id.textItemDescription)
        private val itemPrice: TextView = itemView.findViewById(R.id.textItemPrice)
        private val itemQuantity: TextView = itemView.findViewById(R.id.textItemQuantity)
        private val itemCooldown: TextView = itemView.findViewById(R.id.textItemCooldown)
        private val purchaseButton: MaterialButton = itemView.findViewById(R.id.buttonPurchase)

        fun bind(itemType: FreePassItemType) {
            val context = itemView.context

            // 아이템 정보 설정
            when (itemType) {
                FreePassItemType.DOPAMINE_SHOT -> {
                    itemName.text = context.getString(R.string.item_dopamine_shot)
                    itemDescription.text = context.getString(R.string.item_dopamine_shot_description)
                }
                FreePassItemType.STANDARD_TICKET -> {
                    itemName.text = context.getString(R.string.item_standard_ticket)
                    itemDescription.text = context.getString(R.string.item_standard_ticket_description)
                }
                FreePassItemType.CINEMA_PASS -> {
                    itemName.text = context.getString(R.string.item_cinema_pass)
                    itemDescription.text = context.getString(R.string.item_cinema_pass_description)
                }
            }

            // 가격 및 보유 수량 업데이트
            val price = viewModel.getItemPrice(itemType)
            itemPrice.text = context.getString(R.string.item_price, price)

            // StateFlow 관찰
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                viewModel.itemQuantities.collect { quantities ->
                    val quantity = quantities[itemType] ?: 0
                    if (quantity > 0) {
                        itemQuantity.text = context.getString(R.string.item_quantity, quantity)
                        itemQuantity.visibility = View.VISIBLE
                    } else {
                        itemQuantity.visibility = View.GONE
                    }
                }
            }

            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                viewModel.itemCooldowns.collect { cooldowns ->
                    val cooldown = cooldowns[itemType] ?: 0
                    if (cooldown > 0) {
                        val minutes = TimeUnit.MILLISECONDS.toMinutes(cooldown)
                        val hours = TimeUnit.MILLISECONDS.toHours(cooldown)
                        val cooldownText = if (hours > 0) {
                            "${hours}시간 ${minutes % 60}분"
                        } else {
                            "${minutes}분"
                        }
                        itemCooldown.text = context.getString(R.string.item_cooldown, cooldownText)
                        itemCooldown.visibility = View.VISIBLE
                    } else {
                        itemCooldown.visibility = View.GONE
                    }
                }
            }

            // 구매 버튼 활성화 여부
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                val canPurchase = viewModel.canPurchaseItem(itemType)
                purchaseButton.isEnabled = canPurchase
            }

            purchaseButton.setOnClickListener {
                onPurchaseClick(itemType)
            }
        }
    }
}

/**
 * 인벤토리 어댑터
 */
class InventoryAdapter(
    private val viewModel: ShopViewModel,
    private val onUseClick: (FreePassItemType) -> Unit
) : RecyclerView.Adapter<InventoryAdapter.ViewHolder>() {
    private val items = mutableListOf<FreePassItemType>()

    fun submitList(newItems: List<FreePassItemType>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_shop_item, parent, false)
        return ViewHolder(view, viewModel, onUseClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(
        itemView: View,
        private val viewModel: ShopViewModel,
        private val onUseClick: (FreePassItemType) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val itemName: TextView = itemView.findViewById(R.id.textItemName)
        private val itemDescription: TextView = itemView.findViewById(R.id.textItemDescription)
        private val itemQuantity: TextView = itemView.findViewById(R.id.textItemQuantity)
        private val purchaseButton: MaterialButton = itemView.findViewById(R.id.buttonPurchase)

        fun bind(itemType: FreePassItemType) {
            val context = itemView.context

            // 아이템 정보 설정
            when (itemType) {
                FreePassItemType.DOPAMINE_SHOT -> {
                    itemName.text = context.getString(R.string.item_dopamine_shot)
                    itemDescription.text = context.getString(R.string.item_dopamine_shot_description)
                }
                FreePassItemType.STANDARD_TICKET -> {
                    itemName.text = context.getString(R.string.item_standard_ticket)
                    itemDescription.text = context.getString(R.string.item_standard_ticket_description)
                }
                FreePassItemType.CINEMA_PASS -> {
                    itemName.text = context.getString(R.string.item_cinema_pass)
                    itemDescription.text = context.getString(R.string.item_cinema_pass_description)
                }
            }

            // 보유 수량 표시
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                viewModel.itemQuantities.collect { quantities ->
                    val quantity = quantities[itemType] ?: 0
                    itemQuantity.text = context.getString(R.string.item_quantity, quantity)
                    itemQuantity.visibility = View.VISIBLE
                }
            }

            // 가격 숨기기
            itemView.findViewById<TextView>(R.id.textItemPrice).visibility = View.GONE
            itemView.findViewById<TextView>(R.id.textItemCooldown).visibility = View.GONE

            // 버튼 텍스트 변경
            purchaseButton.text = context.getString(R.string.use)
            purchaseButton.setOnClickListener {
                onUseClick(itemType)
            }
        }
    }
}
