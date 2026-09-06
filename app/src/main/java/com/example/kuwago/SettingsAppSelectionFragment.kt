package com.example.kuwago

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Telephony
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsAppSelectionFragment : Fragment() {

    private lateinit var tvAppsCount: TextView
    private lateinit var btnToggleAll: TextView
    private lateinit var etSearchApps: EditText
    private lateinit var progressLoading: ProgressBar
    private lateinit var tvNoApps: TextView
    private lateinit var rvAppsList: RecyclerView
    private lateinit var adapter: AppSelectionAdapter
    private lateinit var btnMoreOptions: ImageView

    private var allApps: List<InstalledAppInfo> = emptyList()
    private var showOtherApps: Boolean = false

    companion object {
        val DEFAULT_MESSAGING_PACKAGES = setOf(
            "com.facebook.orca",            // Messenger
            "com.facebook.mlite",           // Messenger Lite
            "com.whatsapp",                 // WhatsApp
            "com.whatsapp.w4b",             // WhatsApp Business
            "com.google.android.gm",        // Gmail
            "org.telegram.messenger",       // Telegram
            "org.telegram.messenger.web",   // Telegram Web/Alt
            "com.viber.voip",               // Viber
            "org.thoughtcrime.securesms",   // Signal
            "com.google.android.apps.messaging", // Google Messages
            "com.samsung.android.messaging",     // Samsung Messages
            "com.microsoft.office.outlook", // Outlook
            "com.microsoft.teams",          // Teams
            "com.discord",                  // Discord
            "com.instagram.android",        // Instagram
            "jp.naver.line.android",        // Line
            "com.tencent.mm",               // WeChat
            "com.yahoo.mobile.client.android.mail", // Yahoo Mail
            "com.skype.raider"              // Skype
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_app_selection, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<ImageView>(R.id.btn_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        btnMoreOptions = view.findViewById(R.id.btn_more_options)
        tvAppsCount = view.findViewById(R.id.tv_apps_count)
        btnToggleAll = view.findViewById(R.id.btn_toggle_all)
        etSearchApps = view.findViewById(R.id.et_search_apps)
        progressLoading = view.findViewById(R.id.progress_loading)
        tvNoApps = view.findViewById(R.id.tv_no_apps)
        rvAppsList = view.findViewById(R.id.rv_apps_list)

        rvAppsList.layoutManager = LinearLayoutManager(requireContext())
        adapter = AppSelectionAdapter(emptyList()) { appInfo, isChecked ->
            saveAppToggleState(appInfo.packageName, isChecked)
            updateStatusCount()
        }
        rvAppsList.adapter = adapter

        btnMoreOptions.setOnClickListener { v ->
            showPopupMenu(v)
        }

        etSearchApps.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                applyAppFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnToggleAll.setOnClickListener {
            handleToggleAll()
        }

        loadInstalledApps()
    }

    private fun showPopupMenu(anchor: View) {
        val context = context ?: return
        val popup = PopupMenu(context, anchor)
        val menuTitle = if (showOtherApps) {
            getString(R.string.hide_other_apps)
        } else {
            getString(R.string.show_other_apps)
        }
        popup.menu.add(0, 1, 0, menuTitle)

        popup.setOnMenuItemClickListener { menuItem ->
            if (menuItem.itemId == 1) {
                showOtherApps = !showOtherApps
                applyAppFilter()
                true
            } else {
                false
            }
        }
        popup.show()
    }

    private fun isCommonApp(pkgName: String, appName: String): Boolean {
        if (DEFAULT_MESSAGING_PACKAGES.contains(pkgName)) return true
        val lowerPkg = pkgName.lowercase()
        val lowerName = appName.lowercase()
        return lowerPkg.contains("message") || lowerPkg.contains("sms") ||
                lowerPkg.contains("chat") || lowerPkg.contains("mail") ||
                lowerName.contains("message") || lowerName.contains("chat") ||
                lowerName.contains("mail")
    }

    private fun loadInstalledApps() {
        progressLoading.visibility = View.VISIBLE
        rvAppsList.visibility = View.GONE
        tvNoApps.visibility = View.GONE

        lifecycleScope.launch(Dispatchers.IO) {
            val context = context ?: return@launch
            val pm = context.packageManager
            val currentPkg = context.packageName

            val savedEnabledApps = getSavedEnabledAppPackages(context)

            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolveInfoList = pm.queryIntentActivities(intent, 0)
            val appList = mutableListOf<InstalledAppInfo>()

            for (ri in resolveInfoList) {
                val pkgName = ri.activityInfo.packageName
                if (pkgName == currentPkg) continue // Skip Kuwago

                val appName = ri.loadLabel(pm).toString()
                val icon = ri.loadIcon(pm)
                val isEnabled = savedEnabledApps.contains(pkgName)

                appList.add(InstalledAppInfo(appName, pkgName, icon, isEnabled))
            }

            // Sort: Common apps first (alphabetically), followed by other apps (alphabetically)
            appList.sortWith(Comparator { a, b ->
                val aCommon = isCommonApp(a.packageName, a.appName)
                val bCommon = isCommonApp(b.packageName, b.appName)
                if (aCommon && !bCommon) {
                    -1
                } else if (!aCommon && bCommon) {
                    1
                } else {
                    a.appName.lowercase().compareTo(b.appName.lowercase())
                }
            })

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                allApps = appList
                progressLoading.visibility = View.GONE
                rvAppsList.visibility = View.VISIBLE
                applyAppFilter()
            }
        }
    }

    private fun applyAppFilter() {
        val query = etSearchApps.text.toString().trim().lowercase()

        val filtered = allApps.filter { app ->
            val isCommon = isCommonApp(app.packageName, app.appName)
            val matchesCategory = showOtherApps || isCommon
            val matchesSearch = query.isEmpty() ||
                    app.appName.lowercase().contains(query) ||
                    app.packageName.lowercase().contains(query)

            matchesCategory && matchesSearch
        }

        adapter.updateData(filtered)
        updateStatusCount()
        updateToggleAllButtonText()
        updateEmptyState()
    }

    private fun getSavedEnabledAppPackages(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(SettingsFragment.KEY_ENABLED_APP_PACKAGES)) {
            val defaults = mutableSetOf<String>()
            defaults.addAll(DEFAULT_MESSAGING_PACKAGES)
            val defaultSmsPkg = Telephony.Sms.getDefaultSmsPackage(context)
            if (!defaultSmsPkg.isNull_or_blank()) {
                defaults.add(defaultSmsPkg)
            }
            prefs.edit().putStringSet(SettingsFragment.KEY_ENABLED_APP_PACKAGES, defaults).apply()
            return defaults
        }
        return prefs.getStringSet(SettingsFragment.KEY_ENABLED_APP_PACKAGES, emptySet()) ?: emptySet()
    }

    private fun String?.isNull_or_blank(): Boolean {
        return this == null || this.trim().isEmpty()
    }

    private fun saveAppToggleState(packageName: String, isEnabled: Boolean) {
        val context = context ?: return
        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val currentSet = prefs.getStringSet(SettingsFragment.KEY_ENABLED_APP_PACKAGES, emptySet())?.toMutableSet()
            ?: mutableSetOf()

        if (isEnabled) {
            currentSet.add(packageName)
        } else {
            currentSet.remove(packageName)
        }

        prefs.edit().putStringSet(SettingsFragment.KEY_ENABLED_APP_PACKAGES, currentSet).apply()
        updateToggleAllButtonText()
    }

    private fun updateStatusCount() {
        val visibleList = adapter.getFilteredList()
        val enabledCount = visibleList.count { it.isEnabled }
        val totalCount = visibleList.size
        tvAppsCount.text = getString(R.string.apps_scanned_count, enabledCount, totalCount)
    }

    private fun updateToggleAllButtonText() {
        val visibleList = adapter.getFilteredList()
        val allVisibleEnabled = visibleList.isNotEmpty() && visibleList.all { it.isEnabled }
        btnToggleAll.text = if (allVisibleEnabled) {
            getString(R.string.deselect_all_apps)
        } else {
            getString(R.string.select_all_apps)
        }
    }

    private fun handleToggleAll() {
        val context = context ?: return
        val visibleList = adapter.getFilteredList()
        if (visibleList.isEmpty()) return

        val shouldEnableAll = visibleList.any { !it.isEnabled }

        val prefs = context.getSharedPreferences(SettingsFragment.PREFS_NAME, Context.MODE_PRIVATE)
        val currentSet = prefs.getStringSet(SettingsFragment.KEY_ENABLED_APP_PACKAGES, emptySet())?.toMutableSet()
            ?: mutableSetOf()

        for (app in visibleList) {
            app.isEnabled = shouldEnableAll
            if (shouldEnableAll) {
                currentSet.add(app.packageName)
            } else {
                currentSet.remove(app.packageName)
            }
        }

        prefs.edit().putStringSet(SettingsFragment.KEY_ENABLED_APP_PACKAGES, currentSet).apply()
        adapter.notifyDataSetChanged()
        updateStatusCount()
        updateToggleAllButtonText()
    }

    private fun updateEmptyState() {
        if (adapter.itemCount == 0 && allApps.isNotEmpty()) {
            tvNoApps.visibility = View.VISIBLE
            rvAppsList.visibility = View.GONE
        } else {
            tvNoApps.visibility = View.GONE
            rvAppsList.visibility = View.VISIBLE
        }
    }
}
