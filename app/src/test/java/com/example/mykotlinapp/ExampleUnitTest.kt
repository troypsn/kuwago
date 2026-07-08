package com.example.mykotlinapp

import org.junit.Test
import org.junit.Assert.*
import java.io.File

class ExampleUnitTest {
    
    @Test
    fun testLocalClassificationPipeline() {
        // Find the RF_XGB directory by traversing upwards
        var currentDir: File? = File(".").absoluteFile
        var rfXgbDir = File(currentDir, "RF_XGB")
        while (currentDir != null && !rfXgbDir.exists()) {
            currentDir = currentDir.parentFile
            if (currentDir != null) {
                rfXgbDir = File(currentDir, "RF_XGB")
            }
        }

        assertTrue("RF_XGB directory should exist", rfXgbDir.exists())

        // Read model bytes
        val tfidfBytes = File(rfXgbDir, "tfidf.onnx").readBytes()
        val scalerBytes = File(rfXgbDir, "scaler.onnx").readBytes()
        val rfBytes = File(rfXgbDir, "rf_model.onnx").readBytes()
        val xgbBytes = File(rfXgbDir, "xgboost_model.onnx").readBytes()
        val weightsJson = File(rfXgbDir, "ml_layer_weights.json").readText()

        // Initialize classifier directly
        LocalClassifier.initDirect(tfidfBytes, scalerBytes, rfBytes, xgbBytes, weightsJson)

        // Test safe message
        val safeMessage = "Hello friend! Are you free for coffee later this afternoon?"
        val safeResult = LocalClassifier.classify(null, safeMessage)
        println("Safe message output: probability=${safeResult.probability}, class=${safeResult.classification}")
        assertNotNull(safeResult)
        assertTrue(safeResult.probability in 0f..1f)
        assertNotNull(safeResult.classification)

        // Test smishing message (should trigger urgency, bank name, CTA, and links)
        val smishingMessage = "GCASH: URGENT! CONGRATULATIONS! You won 10000 PHP libre reward premyo agad! Click here to verify now http://bit.ly/gcash-verify-login expires today!"
        val smishingResult = LocalClassifier.classify(null, smishingMessage)
        println("Smishing message output: probability=${smishingResult.probability}, class=${smishingResult.classification}")
        assertNotNull(smishingResult)
        assertTrue(smishingResult.probability in 0f..1f)
        assertNotNull(smishingResult.classification)
    }
}