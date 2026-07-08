package com.example.mykotlinapp

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.nio.FloatBuffer

object LocalClassifier {
    private var env: OrtEnvironment? = null
    private var tfidfSession: OrtSession? = null
    private var scalerSession: OrtSession? = null
    private var rfSession: OrtSession? = null
    private var xgbSession: OrtSession? = null

    // Configuration weights (can be updated dynamically)
    var rfWeight = 0.25f
    var xgbWeight = 0.75f
    var localWeight = 0.5f
    var cnnWeight = 0.5f
    var threshold = 0.45f
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

    fun init(context: Context?) {
        if (isInitialized) return
        if (context == null) return
        synchronized(this) {
            if (isInitialized) return
            try {
                env = OrtEnvironment.getEnvironment()

                // Load weights from assets
                try {
                    val weightsJson = context.assets.open("ml_layer_weights.json").bufferedReader().use { it.readText() }
                    val json = JSONObject(weightsJson)
                    rfWeight = json.optDouble("rf_weight", 0.25).toFloat()
                    xgbWeight = json.optDouble("xgb_weight", 0.75).toFloat()
                    localWeight = json.optDouble("local_weight", 0.5).toFloat()
                    cnnWeight = json.optDouble("cnn_weight", 0.5).toFloat()
                    threshold = json.optDouble("threshold", 0.45).toFloat()
                    suspiciousThreshold = json.optDouble("suspicious_threshold", 0.50).toFloat()
                    smishingThreshold = json.optDouble("smishing_threshold", 0.80).toFloat()
                } catch (e: Exception) {
                    // Fallback to defaults
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
    }

    fun initDirect(
        tfidfBytes: ByteArray,
        scalerBytes: ByteArray,
        rfBytes: ByteArray,
        xgbBytes: ByteArray,
        weightsJson: String
    ) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            try {
                env = OrtEnvironment.getEnvironment()
                try {
                    val json = JSONObject(weightsJson)
                    rfWeight = json.optDouble("rf_weight", 0.25).toFloat()
                    xgbWeight = json.optDouble("xgb_weight", 0.75).toFloat()
                    localWeight = json.optDouble("local_weight", 0.5).toFloat()
                    cnnWeight = json.optDouble("cnn_weight", 0.5).toFloat()
                    threshold = json.optDouble("threshold", 0.45).toFloat()
                    suspiciousThreshold = json.optDouble("suspicious_threshold", 0.50).toFloat()
                    smishingThreshold = json.optDouble("smishing_threshold", 0.80).toFloat()
                } catch (e: Exception) {
                    // Fallback to defaults
                }

                tfidfSession = env?.createSession(tfidfBytes)
                scalerSession = env?.createSession(scalerBytes)
                rfSession = env?.createSession(rfBytes)
                xgbSession = env?.createSession(xgbBytes)

                isInitialized = true
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun readAsset(context: Context, name: String): ByteArray {
        return context.assets.open(name).use { it.readBytes() }
    }

    fun cleanText(text: String): String {
        var t = text
        t = t.replace(Regex("[\\n\\r\\t]+"), " ")
        t = t.replace(Regex("https?://\\S+|www\\.\\S+"), " URL ")
        t = t.replace(Regex("\\+?63[\\d\\*]{9,10}"), "")
        t = t.replace(Regex("\\b0\\d{10}\\b"), "")
        t = t.replace(Regex("\\b\\d{3,4}[-\\s]?\\d{3,4}[-\\s]?\\d{4}\\b"), "")
        val emojiRegex = Regex(
            "[\\uD83D\\uDE00-\\uD83D\\uDE4F\\uD83C\\uDF00-\\uD83D\\uDFFF\\uD83D\\uDE80-\\uD83D\\uDEFF\\uD83C\\uDDE6-\\uD83C\\uDDFF\\u2702-\\u27B0\\u24C2-\\uD83C\\uDF51]+"
        )
        t = t.replace(emojiRegex, "")
        t = t.replace(Regex("[^a-zA-Z0-9\\u00C0-\\u024F\\s.,!?'\\-]"), "")
        t = t.lowercase()
        t = t.replace(Regex("\\s+"), " ").trim()
        return t
    }

    fun preprocessText(text: String): String {
        if (text.isBlank()) return ""
        var t = text
        t = t.replace(Regex("(?<!\\w)\\d+(?!\\w)"), "")
        t = t.replace(Regex("[^a-zA-Z\\s]"), " ")
        t = t.replace(Regex("\\s+"), " ").trim()
        val tokens = t.split(" ").filter { w ->
            (w == "httpurltoken" || w == "phonenumtoken" || !STOPWORDS.contains(w)) && w.length > 1
        }
        return tokens.joinToString(" ")
    }

    fun extractNumericalFeatures(text: String): FloatArray {
        val urls = extractUrls(text)
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
            val url = urls[0]
            hasShortener = if (URL_SHORTENERS.any { s -> url.lowercase().contains(s) }) 1.0f else 0.0f
            hasHttps = if (url.startsWith("https")) 1.0f else 0.0f

            val domainMatch = Regex("https?://([^/]+)").find(url)
            val domain = domainMatch?.groupValues?.get(1) ?: ""
            domainLength = domain.length.toFloat()

            subdomainCount = maxOf(0, domain.count { it == '.' } - 1).toFloat()
            hasIp = if (Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}").containsMatchIn(domain)) 1.0f else 0.0f

            val path = url.replaceFirst(Regex("https?://[^/]+"), "")
            pathDepth = path.split("/").filter { it.isNotEmpty() }.size.toFloat()

            urlSpecialChars = Regex("[-_~%@]").findAll(url).count().toFloat()
        }

        // Structural Features
        val charCount = text.length.toFloat()
        val wordCount = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.size.toFloat()
        val punctCount = Regex("[.,!?]").findAll(text).count().toFloat()

        val digitDensity = if (text.isNotEmpty()) {
            roundTo4(text.count { it.isDigit() }.toFloat() / text.length)
        } else 0.0f
        val upperRatio = if (text.isNotEmpty()) {
            roundTo4(text.count { it.isUpperCase() }.toFloat() / text.length)
        } else 0.0f

        // Behavioral Features
        val textLower = text.lowercase()
        val hasCta = if (CTA_PHRASES.any { p -> textLower.contains(p) }) 1.0f else 0.0f
        val ctaCount = CTA_PHRASES.sumOf { p -> countOccurrences(textLower, p) }.toFloat()

        // PH Context Features
        val hasPhBank = if (PH_BANKS.any { b -> textLower.contains(b) }) 1.0f else 0.0f
        val hasPhTelco = if (PH_TELCOS.any { te -> textLower.contains(te) }) 1.0f else 0.0f
        val hasPhUrgency = if (PH_URGENCY.any { u -> textLower.contains(u) }) 1.0f else 0.0f

        return floatArrayOf(
            urlPresent, urlCount, hasShortener, hasHttps, domainLength, subdomainCount, hasIp, pathDepth, urlSpecialChars,
            charCount, wordCount, punctCount, digitDensity, upperRatio,
            hasCta, ctaCount,
            hasPhBank, hasPhTelco, hasPhUrgency
        )
    }

    private fun extractUrls(text: String): List<String> {
        val matches = Regex("https?://\\S+|www\\.\\S+").findAll(text)
        return matches.map { it.value }.toList()
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

    private fun roundTo4(value: Float): Float {
        return (Math.round(value * 10000.0) / 10000.0).toFloat()
    }

    fun classify(context: Context?, message: String): DetectionResult {
        init(context)
        if (!isInitialized) {
            return fallback(message)
        }

        try {
            // 1. Extract 19 features
            val rawFeatures = extractNumericalFeatures(message)

            // 2. Preprocess text
            val cleaned = cleanText(message)
            val preprocessed = preprocessText(cleaned)

            val localEnv = env ?: return fallback(message)

            // Run scaler.onnx
            val scalerInputTensor = OnnxTensor.createTensor(localEnv, FloatBuffer.wrap(rawFeatures), longArrayOf(1L, 19L))
            val scaledFeatures = scalerInputTensor.use { tensor ->
                val inputs = mapOf("num_input" to tensor as OnnxTensorLike)
                val result = scalerSession?.run(inputs)
                result?.use { res ->
                    val map = res.associate { it.key to it.value }
                    val outputValue = map["variable"] as? OnnxTensor
                    val data = outputValue?.value as? Array<FloatArray>
                    data?.get(0)
                } ?: FloatArray(19)
            }

            // Run tfidf.onnx
            val tfidfInput = arrayOf(arrayOf(preprocessed))
            val tfidfInputTensor = OnnxTensor.createTensor(localEnv, tfidfInput)
            val tfidfFeatures = tfidfInputTensor.use { tensor ->
                val inputs = mapOf("text_input" to tensor as OnnxTensorLike)
                val result = tfidfSession?.run(inputs)
                result?.use { res ->
                    val map = res.associate { it.key to it.value }
                    val outputValue = map["variable"] as? OnnxTensor
                    val data = outputValue?.value as? Array<FloatArray>
                    data?.get(0)
                } ?: FloatArray(5000)
            }

            // Concatenate tfidfFeatures (5000) and scaledFeatures (19) to make combined (5019)
            val combinedFeatures = FloatArray(5019)
            System.arraycopy(tfidfFeatures, 0, combinedFeatures, 0, 5000)
            System.arraycopy(scaledFeatures, 0, combinedFeatures, 5000, 19)

            // Run classifiers
            val combinedInputTensor = OnnxTensor.createTensor(localEnv, FloatBuffer.wrap(combinedFeatures), longArrayOf(1L, 5019L))

            val (rfProb, xgbProb) = combinedInputTensor.use { inputTensor ->
                val rfInputs = mapOf("float_input" to inputTensor as OnnxTensorLike)
                val rfResult = rfSession?.run(rfInputs)
                val rfRawProb = rfResult?.use { res ->
                    val map = res.associate { it.key to it.value }
                    val outputValue = map["probabilities"] as? OnnxTensor
                    val data = outputValue?.value as? Array<FloatArray>
                    data?.get(0)?.get(1) ?: 0.0f
                } ?: 0.0f
                // Apply sigmoid to RF since post_transform is NONE
                val rfProbability = sigmoid(rfRawProb)

                val xgbInputs = mapOf("float_input" to inputTensor as OnnxTensorLike)
                val xgbResult = xgbSession?.run(xgbInputs)
                val xgbProbability = xgbResult?.use { res ->
                    val map = res.associate { it.key to it.value }
                    val outputValue = map["probabilities"] as? OnnxTensor
                    val data = outputValue?.value as? Array<FloatArray>
                    data?.get(0)?.get(1) ?: 0.0f
                } ?: 0.0f

                Pair(rfProbability, xgbProbability)
            }

            val finalProb = rfWeight * rfProb + xgbWeight * xgbProb
            val classification = when {
                finalProb >= smishingThreshold -> Classification.SMISHING
                finalProb >= suspiciousThreshold -> Classification.SUSPICIOUS
                else -> Classification.SAFE
            }

            return DetectionResult(
                sender = "Unknown",
                message = message,
                classification = classification,
                probability = finalProb,
                isScanning = false
            )

        } catch (e: Exception) {
            e.printStackTrace()
            return fallback(message)
        }
    }

    private fun sigmoid(x: Float): Float {
        return (1.0 / (1.0 + Math.exp(-x.toDouble()))).toFloat()
    }

    private fun fallback(message: String): DetectionResult {
        return DetectionResult(
            sender = "Unknown",
            message = message,
            classification = Classification.SAFE,
            probability = 0f,
            isScanning = false
        )
    }
}
