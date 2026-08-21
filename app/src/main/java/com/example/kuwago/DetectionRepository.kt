package com.example.kuwago

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

object DetectionRepository {
    private const val PREFS_NAME = "kuwago_detection_prefs"
    private const val KEY_DETECTIONS = "saved_detections"
    private const val MAX_DETECTIONS = 200

    private val gson = Gson()
    private val _detections = MutableLiveData<List<DetectionResult>>(emptyList())
    val detections: LiveData<List<DetectionResult>> = _detections

    private var appContext: Context? = null
    private var isLoaded = false

    @Synchronized
    fun loadIfNeeded(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
        if (isLoaded) return
        val prefs = (appContext ?: context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_DETECTIONS, null)
        if (!jsonStr.isNullOrEmpty()) {
            try {
                val type = object : TypeToken<List<DetectionResult>>() {}.type
                val savedList: List<DetectionResult> = gson.fromJson(jsonStr, type) ?: emptyList()
                _detections.postValue(savedList)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        isLoaded = true
    }

    private fun persist(list: List<DetectionResult>) {
        val ctx = appContext ?: return
        try {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonStr = gson.toJson(list)
            prefs.edit().putString(KEY_DETECTIONS, jsonStr).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun addDetection(context: Context? = null, result: DetectionResult) {
        context?.let { loadIfNeeded(it) }
        val currentList = _detections.value.orEmpty().toMutableList()
        val index = currentList.indexOfFirst { it.id == result.id || (it.message == result.message && it.sender == result.sender) }
        if (index != -1) {
            currentList[index] = result
        } else {
            currentList.add(0, result)
        }
        if (currentList.size > MAX_DETECTIONS) {
            currentList.removeAt(currentList.size - 1)
        }
        _detections.postValue(currentList)
        persist(currentList)
    }

    fun addDetection(result: DetectionResult) {
        addDetection(null, result)
    }

    fun addDetections(context: Context? = null, results: List<DetectionResult>) {
        context?.let { loadIfNeeded(it) }
        val currentList = _detections.value.orEmpty().toMutableList()
        var changed = false
        for (res in results) {
            val index = currentList.indexOfFirst { it.id == res.id || (it.message == res.message && it.sender == res.sender) }
            if (index == -1) {
                currentList.add(res)
                changed = true
            } else {
                val existing = currentList[index]
                if (res.cnnScore != null && existing.cnnScore == null) {
                    currentList[index] = res
                    changed = true
                }
            }
        }
        if (changed) {
            currentList.sortByDescending { it.timestamp }
            while (currentList.size > MAX_DETECTIONS) {
                currentList.removeAt(currentList.size - 1)
            }
            _detections.postValue(currentList)
            persist(currentList)
        }
    }

    fun addDetections(results: List<DetectionResult>) {
        addDetections(null, results)
    }

    fun updateDetection(context: Context? = null, updatedResult: DetectionResult) {
        context?.let { loadIfNeeded(it) }
        val currentList = _detections.value.orEmpty().toMutableList()
        val index = currentList.indexOfFirst { it.id == updatedResult.id || (it.message == updatedResult.message && it.sender == updatedResult.sender) }
        if (index != -1) {
            currentList[index] = updatedResult
            _detections.postValue(currentList)
            persist(currentList)
        } else {
            addDetection(context, updatedResult)
        }
    }

    fun updateDetection(updatedResult: DetectionResult) {
        updateDetection(null, updatedResult)
    }
}
