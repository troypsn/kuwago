package com.example.mykotlinapp

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object DetectionRepository {
    private val _detections = MutableLiveData<List<DetectionResult>>(emptyList())
    val detections: LiveData<List<DetectionResult>> = _detections

    fun addDetection(result: DetectionResult) {
        val currentList = _detections.value.orEmpty().toMutableList()
        currentList.add(0, result) // Add to the top
        if (currentList.size > 15) { // Increased history slightly
            currentList.removeAt(currentList.size - 1)
        }
        _detections.postValue(currentList)
    }

    fun updateDetection(updatedResult: DetectionResult) {
        val currentList = _detections.value.orEmpty().toMutableList()
        val index = currentList.indexOfFirst { it.id == updatedResult.id }
        if (index != -1) {
            currentList[index] = updatedResult
            _detections.postValue(currentList)
        }
    }
}
