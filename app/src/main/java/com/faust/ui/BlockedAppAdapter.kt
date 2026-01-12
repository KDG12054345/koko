package com.faust.ui

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.faust.R
import com.faust.models.BlockedApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BlockedAppAdapter(
    private val onRemoveClick: (BlockedApp) -> Unit
) : ListAdapter<BlockedApp, BlockedAppAdapter.ViewHolder>(DiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_blocked_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val appIcon: ImageView = itemView.findViewById(R.id.imageAppIcon)
        private val appName: TextView = itemView.findViewById(R.id.textAppName)
        private val packageName: TextView = itemView.findViewById(R.id.textPackageName)
        private val removeButton: com.google.android.material.button.MaterialButton =
            itemView.findViewById(R.id.buttonRemove)

        fun bind(blockedApp: BlockedApp) {
            appName.text = blockedApp.appName
            packageName.text = blockedApp.packageName

            // 앱 아이콘 로드
            loadAppIcon(blockedApp.packageName)

            removeButton.setOnClickListener {
                onRemoveClick(blockedApp)
            }
        }

        private fun loadAppIcon(packageName: String) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val pm = itemView.context.packageManager
                    val appInfo = pm.getApplicationInfo(packageName, 0)
                    val icon: Drawable = pm.getApplicationIcon(appInfo)

                    withContext(Dispatchers.Main) {
                        appIcon.setImageDrawable(icon)
                    }
                } catch (e: Exception) {
                    // 아이콘 로드 실패 시 기본 아이콘 사용
                    withContext(Dispatchers.Main) {
                        appIcon.setImageResource(android.R.drawable.sym_def_app_icon)
                    }
                }
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<BlockedApp>() {
        override fun areItemsTheSame(oldItem: BlockedApp, newItem: BlockedApp): Boolean {
            return oldItem.packageName == newItem.packageName
        }

        override fun areContentsTheSame(oldItem: BlockedApp, newItem: BlockedApp): Boolean {
            return oldItem == newItem
        }
    }
}
