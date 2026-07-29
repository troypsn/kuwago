package com.example.mykotlinapp

import org.junit.Test
import org.junit.Assert.*

class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun testLocalClassificationPipeline() {
        // Initialize the local classifier (context=null will trigger the file system fallback)
        LocalClassifier.initialize(null)

        // Test safe message
        val safeMessage = "Hello friend! Are you free for coffee later this afternoon?"
        val safeResult = LocalClassifier.classify(null, safeMessage)
        println("Safe message output: probability=${safeResult.probability}, class=${safeResult.classification}")
        assertNotNull(safeResult)
        assertTrue(safeResult.probability in 0f..1f)
        assertNotNull(safeResult.classification)

        // Test smishing message
        val smishingMessage = "GCASH: URGENT! CONGRATULATIONS! You won 10000 PHP libre reward premyo agad! Click here to verify now http://bit.ly/gcash-verify-login expires today!"
        val smishingResult = LocalClassifier.classify(null, smishingMessage)
        println("Smishing message output: probability=${smishingResult.probability}, class=${smishingResult.classification}")
        assertNotNull(smishingResult)
        assertTrue(smishingResult.probability in 0f..1f)
        assertNotNull(smishingResult.classification)
    }
}