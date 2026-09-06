package com.example.kuwago

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView

data class InstalledAppInfo(
    val appName: String,
    val packageName: String,
    val icon: Drawable,
    var isEnabled: Boolean
)

class AppSelectionAdapter(
    private var appsList: List<InstalledAppInfo>,
    private val onToggleChanged: (InstalledAppInfo, Boolean) -> Unit
) : RecyclerView.Adapter<AppSelectionAdapter.AppViewHolder>() {

    private var filteredList: List<InstalledAppInfo> = appsList

    class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imgIcon: ImageView = itemView.findViewById(R.id.img_app_icon)
        val tvAppName: TextView = itemView.findViewById(R.id.tv_app_name)
        val tvPackageName: TextView = itemView.findViewById(R.id.tv_package_name)
        val switchScan: SwitchCompat = itemView.findViewById(R.id.switch_app_scan)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app_selection, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = filteredList[position]
        holder.imgIcon.setImageDrawable(app.icon)
        holder.tvAppName.text = app.appName
        holder.tvPackageName.text = app.packageName

        // Remove listener temporarily before setting checked status to avoid trigger loops
        holder.switchScan.setOnCheckedChangeListener(null)
        holder.switchScan.isChecked = app.isEnabled

        holder.switchScan.setOnCheckedChangeListener { _, isChecked ->
            app.isEnabled = isChecked
            onToggleChanged(app, isChecked)
        }

        holder.itemView.setOnClickListener {
            holder.switchScan.toggle()
        }
    }

    override fun getItemCount(): Int = filteredList.size

    fun filter(query: String) {
        filteredList = if (query.isBlank()) {
            appsList
        } else {
            val q = query.trim().lowercase()
            appsList.filter {
                it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
        notifyDataSetChanged()
    }

    fun updateData(newList: List<InstalledAppInfo>) {
        appsList = newList
        filteredList = newList
        notifyDataSetChanged()
    }

    fun getFilteredList(): List<InstalledAppInfo> = filteredList
}
