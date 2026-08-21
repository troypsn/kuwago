package com.example.kuwago

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object DetectionRepository {
    private val _detections = MutableLiveData<List<DetectionResult>>(emptyList())
    val detections: LiveData<List<DetectionResult>> = _detections

    fun addDetection(result: DetectionResult) {
        val currentList = _detections.value.orEmpty().toMutableList()
        val index = currentList.indexOfFirst { it.message == result.message && it.sender == result.sender }
        if (index != -1) {
            currentList[index] = result
        } else {
            currentList.add(0, result) // Add to the top
        }
        if (currentList.size > 100) {
            currentList.removeAt(currentList.size - 1)
        }
        _detections.postValue(currentList)
    }

    fun addDetections(results: List<DetectionResult>) {
        val currentList = _detections.value.orEmpty().toMutableList()
        var changed = false
        for (res in results) {
            val index = currentList.indexOfFirst { it.message == res.message && it.sender == res.sender }
            if (index == -1) {
                currentList.add(res)
                changed = true
            }
        }
        if (changed) {
            currentList.sortByDescending { it.timestamp }
            if (currentList.size > 100) {
                while (currentList.size > 100) {
                    currentList.removeAt(currentList.size - 1)
                }
            }
            _detections.postValue(currentList)
        }
    }

    fun updateDetection(updatedResult: DetectionResult) {
        val currentList = _detections.value.orEmpty().toMutableList()
        val index = currentList.indexOfFirst { it.id == updatedResult.id || (it.message == updatedResult.message && it.sender == updatedResult.sender) }
        if (index != -1) {
            currentList[index] = updatedResult
            _detections.postValue(currentList)
        }
    }
}
