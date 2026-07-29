package com.example.mykotlinapp

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.nio.FloatBuffer
import java.util.regex.Pattern

object LocalClassifier {
    private var env: OrtEnvironment? = null
    private var tfidfSession: OrtSession? = null
    private var scalerSession: OrtSession? = null
    private var rfSession: OrtSession? = null
    private var xgbSession: OrtSession? = null

    // Constants for model dimensions
    private const val TFIDF_FEATURES_COUNT = 1500
    private const val NUMERICAL_FEATURES_COUNT = 19
    private const val TOTAL_FEATURES_COUNT = 1519

    // Default configuration weights (can be updated dynamically or loaded from JSON)
    var rfWeight = 0.75f
    var xgbWeight = 0.25f
    var localWeight = 0.50f
    var cnnWeight = 0.50f
    var suspiciousThreshold = 0.50f
    var smishingThreshold = 0.80f

    private var isInitialized = false

    private val STOPWORDS = setOf(
        "kung", "any", "shouldn't", "naman", "para", "sila", "by", "did", "they're", "under", "mo", "it'd", "alin", "isn", "because", "pa", "d", "couldn", "to", "your", "it's", "himself", "was", "her", "nang", "some", "siya", "kasi", "own", "has", "sino", "he'll", "are", "being", "you", "for", "between", "itself", "it'll", "ourselves", "mga", "ma", "not", "again", "now", "shan", "nila", "at", "out", "she'll", "have", "m", "more", "pala", "you'll", "above", "on", "shouldn", "their", "mightn", "dito", "din", "yours", "should", "you'd", "is", "into", "ll", "through", "them", "ko", "were", "no", "having", "our", "be", "myself", "re", "pero", "and", "nor", "yourself", "will", "she", "wouldn", "all", "ka", "iyon", "he", "theirs", "aren't", "once", "same", "weren't", "me", "how", "we've", "hadn't", "ang", "wala", "needn", "had", "during", "haven", "am", "couldn't", "why", "themselves", "lang", "i'm", "we're", "just", "that'll", "a", "saan", "na", "yung", "up", "they've", "ain", "natin", "rin", "yourselves", "ours", "namin", "who", "off", "kami", "opo", "hindi", "where", "as", "o", "such", "didn't", "against", "t", "s", "few", "herself", "he's", "before", "wasn", "niya", "when", "so", "doesn't", "you're", "may", "if", "haven't", "mustn", "or", "shan't", "then", "they'll", "raw", "aren", "bakit", "mightn't", "i'd", "hasn't", "we", "do", "i'll", "my", "daw", "can", "from", "doesn", "ba", "you've", "po", "weren", "tayo", "but", "other", "hasn", "below", "won", "most", "after", "each", "does", "the", "she'd", "he'd", "don't", "wasn't", "don", "didn", "ng", "that", "doing", "we'd", "i've", "whom", "won't", "i", "wouldn't", "him", "than", "its", "there", "both", "in", "what", "talaga", "until", "we'll", "ano", "here", "down", "about", "y", "too", "they'd", "should've", "of", "doon", "hadn", "been", "ay", "hers", "very", "mustn't", "with", "they", "nga", "an", "this", "ho", "ve", "she's", "further", "his", "these", "sa", "those", "isn't", "needn't", "ito", "while", "only", "which", "it"
    )

    private val PH_BANKS = listOf(
        "bdo", "bpi", "metrobank", "landbank", "rcbc", "unionbank",
        "eastwest", "psbank", "chinabank", "security bank", "pnb",
        "gcash", "maya", "paymaya", "gotyme", "seabank", "tonik"
    )
    private val PH_TELCOS = listOf("smart", "globe", "tnt", "sun", "dito", "gomo", "tm")
    private val PH_URGENCY = listOf(
        "agad", "ngayon", "mawala", "deadline", "huling araw", "expir",
        "panalo", "manalo", "libreng", "libre", "premyo", "reward",
        "kunin", "i-click", "i-verify", "i-update", "i-confirm",
        "mag-claim", "i-redeem", "i-activate", "mag-log", "mag-login"
    )
    private val URL_SHORTENERS = listOf(
        "bit.ly", "tinyurl", "t.co", "goo.gl", "ow.ly",
        "short.link", "rb.gy", "cutt.ly", "tiny.cc", "is.gd"
    )
    private val CTA_PHRASES = listOf(
        "click here", "verify now", "claim your", "act now", "limited time",
        "expires today", "call now", "text now", "reply now", "visit now",
        "click link", "tap here", "open now", "log in now", "sign in now",
        "update now", "confirm now", "validate now", "redeem now"
    )

    private fun readAsset(context: Context?, filename: String): ByteArray {
        if (context != null) {
            return context.assets.open(filename).use { it.readBytes() }
        }
        val userDir = System.getProperty("user.dir") ?: "."
        val paths = listOf(
            java.io.File(userDir, "src/main/assets/$filename"),
            java.io.File(userDir, "app/src/main/assets/$filename"),
            java.io.File(userDir, "kuwago/app/src/main/assets/$filename")
        )
        for (f in paths) {
            if (f.exists()) {
                return f.readBytes()
            }
        }
        throw java.io.FileNotFoundException("Could not find asset $filename in paths: $paths")
    }

    @Synchronized
    fun initialize(context: Context?) {
        if (isInitialized) return
        try {
            env = OrtEnvironment.getEnvironment()
            
            // Try loading weights from config JSON
            try {
                val jsonStr = String(readAsset(context, "ml_layer_weights.json"), Charsets.UTF_8)
                val json = JSONObject(jsonStr)
                rfWeight = json.optDouble("rf_weight", rfWeight.toDouble()).toFloat()
                xgbWeight = json.optDouble("xgb_weight", xgbWeight.toDouble()).toFloat()
                localWeight = json.optDouble("local_weight", localWeight.toDouble()).toFloat()
                cnnWeight = json.optDouble("cnn_weight", cnnWeight.toDouble()).toFloat()
                suspiciousThreshold = json.optDouble("suspicious_threshold", suspiciousThreshold.toDouble()).toFloat()
                if (json.has("threshold") && !json.has("suspicious_threshold")) {
                    suspiciousThreshold = json.optDouble("threshold", suspiciousThreshold.toDouble()).toFloat()
                }
                smishingThreshold = json.optDouble("smishing_threshold", smishingThreshold.toDouble()).toFloat()
            } catch (e: Exception) {
                // Ignore and use defaults
            }

            tfidfSession = env?.createSession(readAsset(context, "tfidf.onnx"))
            scalerSession = env?.createSession(readAsset(context, "scaler.onnx"))
            rfSession = env?.createSession(readAsset(context, "rf_model.onnx"))
            xgbSession = env?.createSession(readAsset(context, "xgboost_model.onnx"))
            isInitialized = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun cleanText(text: String): String {
        var t = text
        t = t.replace(Regex("[\\n\\r\\t]+"), " ")
        t = t.replace(Regex("https?://\\S+|www\\.\\S+"), " URL ")
        t = t.replace(Regex("\\+?63[\\d\\*]{9,10}"), "")
        t = t.replace(Regex("\\b0\\d{10}\\b"), "")
        t = t.replace(Regex("\\b\\d{3,4}[-\\s]?\\d{3,4}[-\\s]?\\d{4}\\b"), "")
        
        // Remove emoji unicode blocks
        val emojiPattern = Pattern.compile(
            "[\\uD83C-\\uDBFF\\uDC00-\\uDFFF]+",
            Pattern.UNICODE_CASE
        )
        t = emojiPattern.matcher(t).replaceAll("")
        
        t = t.replace(Regex("[^a-zA-Z0-9\\u00C0-\\u024F\\s.,!?'\\-]"), "")
        t = t.lowercase()
        t = t.replace(Regex("\\s+"), " ").trim()
        return t
    }

    fun preprocessText(text: String): String {
        if (text.trim().isEmpty()) return ""
        var t = text
        t = t.replace(Regex("(?<!\\w)\\d+(?!\\w)"), "")
        t = t.replace(Regex("[^a-zA-Z\\s]"), " ")
        t = t.replace(Regex("\\s+"), " ").trim()
        
        val tokens = t.split(" ")
            .map { it.trim() }
            .filter { it !in STOPWORDS && it.length > 1 }
        return tokens.joinToString(" ")
    }

    private fun countOccurrences(text: String, sub: String): Int {
        var count = 0
        var idx = 0
        while (true) {
            idx = text.indexOf(sub, idx)
            if (idx != -1) {
                count++
                idx += sub.length
            } else {
                break
            }
        }
        return count
    }

    fun extractNumericalFeatures(text: String): FloatArray {
        val urls = Regex("https?://\\S+|www\\.\\S+").findAll(text).map { it.value }.toList()
        val urlPresent = if (urls.isNotEmpty()) 1.0f else 0.0f
        val urlCount = urls.size.toFloat()
        var hasShortener = 0.0f
        var hasHttps = 0.0f
        var domainLength = 0.0f
        var subdomainCount = 0.0f
        var hasIp = 0.0f
        var pathDepth = 0.0f
        var urlSpecialChars = 0.0f

        if (urls.isNotEmpty()) {
            val url = urls[0].lowercase()
            hasShortener = if (URL_SHORTENERS.any { url.contains(it) }) 1.0f else 0.0f
            hasHttps = if (url.startsWith("https")) 1.0f else 0.0f
            
            val domainMatch = Regex("https?://([^/]+)").find(url)
            val domain = domainMatch?.groupValues?.get(1) ?: ""
            domainLength = domain.length.toFloat()
            subdomainCount = domain.count { it == '.' }.minus(1).coerceAtLeast(0).toFloat()
            hasIp = if (Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}").containsMatchIn(domain)) 1.0f else 0.0f
            
            val path = url.replace(domainMatch?.value ?: "", "")
            pathDepth = path.split("/").filter { it.isNotEmpty() }.size.toFloat()
            urlSpecialChars = url.count { it in "-_~%@" }.toFloat()
        }

        val charCount = text.length.toFloat()
        val wordCount = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.size.toFloat()
        val punctCount = text.count { it in ".,!?" }.toFloat()
        
        val digitDensity = if (text.isNotEmpty()) {
            (text.count { it.isDigit() }.toFloat() / text.length.toFloat())
        } else 0.0f
        
        val upperRatio = if (text.isNotEmpty()) {
            (text.count { it.isUpperCase() }.toFloat() / text.length.toFloat())
        } else 0.0f

        val textLower = text.lowercase()
        val hasCta = if (CTA_PHRASES.any { textLower.contains(it) }) 1.0f else 0.0f
        val ctaCount = CTA_PHRASES.sumOf { countOccurrences(textLower, it) }.toFloat()
        
        val hasPhBank = if (PH_BANKS.any { textLower.contains(it) }) 1.0f else 0.0f
        val hasPhTelco = if (PH_TELCOS.any { textLower.contains(it) }) 1.0f else 0.0f
        val hasPhUrgency = if (PH_URGENCY.any { textLower.contains(it) }) 1.0f else 0.0f

        return floatArrayOf(
            urlPresent, urlCount, hasShortener, hasHttps, domainLength, subdomainCount, hasIp, pathDepth, urlSpecialChars,
            charCount, wordCount, punctCount, digitDensity, upperRatio,
            hasCta, ctaCount,
            hasPhBank, hasPhTelco, hasPhUrgency
        )
    }

    private fun sigmoid(x: Float): Float {
        return (1.0f / (1.0f + Math.exp(-x.toDouble()).toFloat()))
    }

    fun classify(context: Context?, message: String): DetectionResult {
        if (!isInitialized) {
            initialize(context)
        }

        if (tfidfSession == null || scalerSession == null || rfSession == null || xgbSession == null) {
            return DetectionResult(
                sender = "Unknown",
                message = message,
                classification = Classification.SAFE,
                probability = 0.0f,
                isScanning = false
            )
        }

        val cleaned = cleanText(message)
        val prep = preprocessText(cleaned)
        val rawNum = extractNumericalFeatures(message)

        val envLocal = env ?: OrtEnvironment.getEnvironment()

        // 1. Scale numerical features (19 dimensions)
        val scalerInputTensor = OnnxTensor.createTensor(
            envLocal,
            FloatBuffer.wrap(rawNum),
            longArrayOf(1L, NUMERICAL_FEATURES_COUNT.toLong())
        )
        val scaledNum = scalerInputTensor.use { tensor ->
            val inputs = mapOf("num_input" to tensor)
            val result = scalerSession?.run(inputs)
            val arr = FloatArray(NUMERICAL_FEATURES_COUNT)
            if (result != null) {
                try {
                    val outTensor = result.get(0) as OnnxTensor
                    val floatBuf = outTensor.floatBuffer
                    floatBuf.rewind()
                    floatBuf.get(arr)
                } finally {
                    result.close()
                }
            }
            arr
        }

        // 2. Vectorize text with TFIDF (1500 dimensions)
        val textInputTensor = OnnxTensor.createTensor(
            envLocal,
            arrayOf(prep),
            longArrayOf(1L, 1L)
        )
        val textTfidf = textInputTensor.use { tensor ->
            val inputs = mapOf("text_input" to tensor)
            val result = tfidfSession?.run(inputs)
            val arr = FloatArray(TFIDF_FEATURES_COUNT)
            if (result != null) {
                try {
                    val outTensor = result.get(0) as OnnxTensor
                    val floatBuf = outTensor.floatBuffer
                    floatBuf.rewind()
                    floatBuf.get(arr)
                } finally {
                    result.close()
                }
            }
            arr
        }

        // 3. Concatenate (1500 text features + 19 numerical features = 1519)
        val combinedInput = FloatArray(TOTAL_FEATURES_COUNT)
        System.arraycopy(textTfidf, 0, combinedInput, 0, TFIDF_FEATURES_COUNT)
        System.arraycopy(scaledNum, 0, combinedInput, TFIDF_FEATURES_COUNT, NUMERICAL_FEATURES_COUNT)

        // 4. Run Classifier inference (1519 dimensions)
        val combinedInputTensor = OnnxTensor.createTensor(
            envLocal,
            FloatBuffer.wrap(combinedInput),
            longArrayOf(1L, TOTAL_FEATURES_COUNT.toLong())
        )
        var rfProb = 0.0f
        var rfRawLogit = 0.0f
        var xgbProb = 0.0f

        combinedInputTensor.use { tensor ->
            val inputs = mapOf("float_input" to tensor)
            
            // Random Forest
            val rfResult = rfSession?.run(inputs)
            if (rfResult != null) {
                try {
                    val probValue = rfResult.get(1)
                    if (probValue is OnnxTensor) {
                        val floatBuf = probValue.floatBuffer
                        floatBuf.rewind()
                        rfRawLogit = floatBuf.get(1)
                        rfProb = sigmoid(rfRawLogit)
                    }
                } finally {
                    rfResult.close()
                }
            }

            // XGBoost
            val xgbResult = xgbSession?.run(inputs)
            if (xgbResult != null) {
                try {
                    val probValue = xgbResult.get(1)
                    if (probValue is OnnxTensor) {
                        val floatBuf = probValue.floatBuffer
                        floatBuf.rewind()
                        xgbProb = floatBuf.get(1)
                    }
                } finally {
                    xgbResult.close()
                }
            }
        }

        // Compute ensembled local score (75% Random Forest, 25% XGBoost)
        val localProb = rfWeight * rfProb + xgbWeight * xgbProb

        val classification = when {
            localProb >= smishingThreshold -> Classification.SMISHING
            localProb >= suspiciousThreshold -> Classification.SUSPICIOUS
            else -> Classification.SAFE
        }

        return DetectionResult(
            sender = "Unknown",
            message = message,
            classification = classification,
            probability = localProb,
            isScanning = false,
            rfProb = rfProb,
            rfRawLogit = rfRawLogit,
            xgbProb = xgbProb,
            cnnProb = null
        )
    }

    fun formatDetailsExplanation(result: DetectionResult): String {
        val rfProbStr = String.format(java.util.Locale.US, "%.4f", result.rfProb)
        val rfLogitStr = String.format(java.util.Locale.US, "%.4f", result.rfRawLogit)
        val xgbProbStr = String.format(java.util.Locale.US, "%.4f", result.xgbProb)
        val localEnsembleScore = rfWeight * result.rfProb + xgbWeight * result.xgbProb
        val localScoreStr = String.format(java.util.Locale.US, "%.4f", localEnsembleScore)
        val cnnScoreStr = if (result.cnnProb != null) result.cnnProb.toString() else "N/A"
        
        return "Individual Model Predictions:\n" +
               "  - Random Forest Prob (scaled):  $rfProbStr (raw logit: $rfLogitStr)\n" +
               "  - XGBoost Prob:                 $xgbProbStr\n" +
               "  - Ensembled Local Score (${(rfWeight*100).toInt()}-${(xgbWeight*100).toInt()}): $localScoreStr\n" +
               "  - Remote CNN API Score:         $cnnScoreStr"
    }
}