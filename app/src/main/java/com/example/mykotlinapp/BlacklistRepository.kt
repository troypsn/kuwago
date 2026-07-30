package com.example.mykotlinapp

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import org.json.JSONArray
import org.json.JSONObject

object BlacklistRepository {

    private const val PREFS_NAME = "kuwago_blacklist_prefs"
    private const val KEY_ENTRIES = "blacklist_entries"
    private const val KEY_AUTO_BLACKLIST = "auto_blacklist_enabled"
    private const val KEY_ACKNOWLEDGED_WARNINGS = "acknowledged_warnings"

    private val _blacklistLiveData = MutableLiveData<List<BlacklistEntry>>(emptyList())
    val blacklistLiveData: LiveData<List<BlacklistEntry>> = _blacklistLiveData

    private var isLoaded = false

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun loadIfNeeded(context: Context) {
        if (isLoaded) return
        val prefs = getPrefs(context)
        val jsonStr = prefs.getString(KEY_ENTRIES, null)
        val list = mutableListOf<BlacklistEntry>()

        if (!jsonStr.isNullOrEmpty()) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val sender = obj.getString("sender")
                    // Filter out legacy placeholder data
                    if (sender == "BDO" || sender == "+63 949 651 0557" || sender == "LTO") {
                        continue
                    }
                    list.add(
                        BlacklistEntry(
                            id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                            sender = sender,
                            riskLevel = RiskLevel.valueOf(obj.optString("riskLevel", "HIGH")),
                            flaggedCount = obj.optInt("flaggedCount", 1),
                            method = BlacklistMethod.valueOf(obj.optString("method", "AUTO")),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        saveList(context, list)
        _blacklistLiveData.postValue(list)
        isLoaded = true
    }

    private fun saveList(context: Context, list: List<BlacklistEntry>) {
        val array = JSONArray()
        for (entry in list) {
            val obj = JSONObject().apply {
                put("id", entry.id)
                put("sender", entry.sender)
                put("riskLevel", entry.riskLevel.name)
                put("flaggedCount", entry.flaggedCount)
                put("method", entry.method.name)
                put("timestamp", entry.timestamp)
            }
            array.put(obj)
        }
        getPrefs(context).edit().putString(KEY_ENTRIES, array.toString()).apply()
        _blacklistLiveData.postValue(list)
    }

    fun getBlacklist(context: Context): List<BlacklistEntry> {
        loadIfNeeded(context)
        return _blacklistLiveData.value.orEmpty()
    }

    fun isBlacklisted(context: Context, sender: String): Boolean {
        if (sender.isBlank()) return false
        val list = getBlacklist(context)
        val cleanSender = normalizeSender(sender)
        if (cleanSender.isEmpty()) return false

        return list.any { entry ->
            val cleanEntry = normalizeSender(entry.sender)
            cleanEntry == cleanSender ||
            (cleanEntry.length >= 4 && cleanSender.contains(cleanEntry)) ||
            (cleanSender.length >= 4 && cleanEntry.contains(cleanSender))
        }
    }

    fun addOrUpdateEntry(
        context: Context,
        sender: String,
        riskLevel: RiskLevel = RiskLevel.HIGH,
        method: BlacklistMethod = BlacklistMethod.AUTO
    ) {
        if (sender.isBlank()) return
        loadIfNeeded(context)
        val currentList = _blacklistLiveData.value.orEmpty().toMutableList()
        val cleanSender = normalizeSender(sender)
        val index = currentList.indexOfFirst { normalizeSender(it.sender) == cleanSender }

        if (index != -1) {
            val existing = currentList[index]
            currentList[index] = existing.copy(
                flaggedCount = existing.flaggedCount + 1,
                riskLevel = riskLevel,
                timestamp = System.currentTimeMillis()
            )
        } else {
            currentList.add(
                0,
                BlacklistEntry(
                    sender = sender,
                    riskLevel = riskLevel,
                    flaggedCount = 1,
                    method = method,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
        saveList(context, currentList)
    }

    fun removeEntry(context: Context, sender: String) {
        if (sender.isBlank()) return
        loadIfNeeded(context)
        val currentList = _blacklistLiveData.value.orEmpty().toMutableList()
        val cleanSender = normalizeSender(sender)
        val removed = currentList.removeAll { 
            val cleanEntry = normalizeSender(it.sender)
            cleanEntry == cleanSender || (cleanEntry.length >= 4 && cleanSender.contains(cleanEntry))
        }
        if (removed) {
            saveList(context, currentList)
        }
    }

    fun isAutoBlacklistEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_BLACKLIST, false)
    }

    fun setAutoBlacklistEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_BLACKLIST, enabled).apply()
    }

    fun markWarningAcknowledged(context: Context, sender: String) {
        val prefs = getPrefs(context)
        val set = prefs.getStringSet(KEY_ACKNOWLEDGED_WARNINGS, emptySet())?.toMutableSet() ?: mutableSetOf()
        set.add(normalizeSender(sender))
        prefs.edit().putStringSet(KEY_ACKNOWLEDGED_WARNINGS, set).apply()
    }

    fun isWarningAcknowledged(context: Context, sender: String): Boolean {
        val set = getPrefs(context).getStringSet(KEY_ACKNOWLEDGED_WARNINGS, emptySet()).orEmpty()
        val cleanSender = normalizeSender(sender)
        return set.contains(cleanSender)
    }

    fun normalizeSender(sender: String): String {
        var clean = sender.trim().lowercase().replace(Regex("[^a-zA-Z0-9]"), "")
        if (clean.startsWith("639") && clean.length == 12) {
            clean = "09" + clean.substring(3)
        } else if (clean.startsWith("9") && clean.length == 10) {
            clean = "0" + clean
        }
        return clean
    }
}
