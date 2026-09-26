package com.example.kuwago

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
    private const val NUMERICAL_FEATURES_COUNT = 21
    private const val TOTAL_FEATURES_COUNT = 1521

    // Default configuration weights (can be updated dynamically or loaded from JSON)
    var rfWeight = 0.75f
    var xgbWeight = 0.25f
    var localWeight = 0.50f
    var cnnWeight = 0.50f
    var suspiciousThreshold = 0.50f
    var smishingThreshold = 0.85f

    val isInitialized: Boolean
        get() = (env != null && (rfSession != null || xgbSession != null) && tfidfSession != null && scalerSession != null)

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

    private fun getModelFilePath(context: Context?, filename: String): String {
        if (context == null) {
            val userDir = System.getProperty("user.dir") ?: "."
            val paths = listOf(
                java.io.File(userDir, "src/main/assets/$filename"),
                java.io.File(userDir, "app/src/main/assets/$filename"),
                java.io.File(userDir, "kuwago/app/src/main/assets/$filename")
            )
            for (f in paths) {
                if (f.exists()) return f.absolutePath
            }
            throw java.io.FileNotFoundException("Could not find asset $filename in paths: $paths")
        }

        val modelsDir = java.io.File(context.filesDir, "onnx_models")
        if (!modelsDir.exists()) {
            modelsDir.mkdirs()
        }
        val targetFile = java.io.File(modelsDir, filename)

        val assetFd = try {
            context.assets.openFd(filename)
        } catch (e: Exception) {
            null
        }

        val assetLength = assetFd?.length ?: -1L
        assetFd?.close()

        val needsCopy = !targetFile.exists() || (assetLength > 0 && targetFile.length() != assetLength)

        if (needsCopy) {
            context.assets.open(filename).use { input ->
                java.io.FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                }
            }
        }

        return targetFile.absolutePath
    }

    @Synchronized
    fun initialize(context: Context?) {
        if (isInitialized) return
        try {
            if (env == null) {
                env = OrtEnvironment.getEnvironment()
            }
            
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

            if (tfidfSession == null) {
                try {
                    tfidfSession = env?.createSession(getModelFilePath(context, "tfidf.onnx"))
                } catch (t: Throwable) {
                    android.util.Log.e("LocalClassifier", "Error loading tfidf.onnx", t)
                }
            }

            if (scalerSession == null) {
                try {
                    scalerSession = env?.createSession(getModelFilePath(context, "scaler.onnx"))
                } catch (t: Throwable) {
                    android.util.Log.e("LocalClassifier", "Error loading scaler.onnx", t)
                }
            }

            if (rfSession == null) {
                try {
                    rfSession = env?.createSession(getModelFilePath(context, "rf_model.onnx"))
                } catch (t: Throwable) {
                    android.util.Log.w("LocalClassifier", "Could not load rf_model.onnx (possibly low memory/32-bit device), will rely on XGBoost/heuristics", t)
                    rfSession = null
                }
            }

            if (xgbSession == null) {
                try {
                    xgbSession = env?.createSession(getModelFilePath(context, "xgb_model.onnx"))
                } catch (t: Throwable) {
                    android.util.Log.w("LocalClassifier", "Could not load xgb_model.onnx", t)
                    xgbSession = null
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e("LocalClassifier", "Fatal error during LocalClassifier initialization", e)
        }
    }

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
    const val SHORTENED_URL_REASONING =
        "The URL is hidden behind a URL shortening process which hides the website intentions, destination, and other possible information, commonly used to conceal malicious links."

    val URL_SHORTENER_REGEX: Pattern = Pattern.compile(
        """(?i)\b(?:https?://|www\.)?(?:bit\.ly|tinyurl\.com|tinyurl|t\.co|goo\.gl|ow\.ly|short\.link|rb\.gy|cutt\.ly|tiny\.cc|is\.gd|buff\.ly|adf\.ly|bit\.do|shorturl\.at|t\.ly|v\.gd|clck\.ru|s\.id|rebrand\.ly|bl\.ink|surl\.li|rotf\.lol|tiny\.one|qr\.ae|ity\.im|bc\.vc|twitthis\.com|u\.to|j\.mp|buzurl\.com|cutt\.us|u\.bb|yourls\.org|prettylinkpro\.com|scrnch\.me|filoops\.info|vzturl\.com|qr\.net|1url\.com|tweez\.me|v\.ht|tr\.im|linktr\.ee|qrs\.ly|soo\.gd|tny\.im|chilp\.it)(?:/[a-zA-Z0-9_\-\./%+?&=#]*)?""",
        Pattern.CASE_INSENSITIVE
    )

    val KNOWN_SHORTENER_DOMAINS = listOf(
        "bit.ly", "tinyurl.com", "tinyurl", "t.co", "goo.gl", "ow.ly",
        "short.link", "rb.gy", "cutt.ly", "tiny.cc", "is.gd",
        "buff.ly", "adf.ly", "bit.do", "shorturl.at", "t.ly", "v.gd",
        "clck.ru", "s.id", "rebrand.ly", "bl.ink", "surl.li",
        "rotf.lol", "tiny.one", "qr.ae", "ity.im", "bc.vc",
        "twitthis.com", "u.to", "j.mp", "buzurl.com", "cutt.us",
        "u.bb", "yourls.org", "qr.net", "linktr.ee", "qrs.ly",
        "soo.gd", "tny.im", "chilp.it"
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

    fun isShortenedUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val clean = url.trim().lowercase()
        if (URL_SHORTENER_REGEX.matcher(clean).find()) return true

        val host = UrlNormalizer.extractHost(clean) ?: clean.removePrefix("http://").removePrefix("https://").substringBefore("/").substringBefore("?")
        return KNOWN_SHORTENER_DOMAINS.any { shortener ->
            host == shortener || host.endsWith(".$shortener") || clean.contains("$shortener/")
        }
    }

    fun containsShortenedUrl(text: String): Boolean {
        if (text.isBlank()) return false
        val extracted = extractUrl(text)
        if (extracted != null && isShortenedUrl(extracted)) return true
        return URL_SHORTENER_REGEX.matcher(text).find()
    }

    fun findMatchedBanks(text: String): List<String> {
        val lower = text.lowercase()
        return PH_BANKS.filter { lower.contains(it) }
    }

    fun findMatchedTelcos(text: String): List<String> {
        val lower = text.lowercase()
        return PH_TELCOS.filter { lower.contains(it) }
    }

    fun findMatchedUrgency(text: String): List<String> {
        val lower = text.lowercase()
        return PH_URGENCY.filter { lower.contains(it) }
    }

    fun findMatchedCta(text: String): List<String> {
        val lower = text.lowercase()
        return CTA_PHRASES.filter { lower.contains(it) }
    }

    fun classifyWithHeuristics(message: String): DetectionResult {
        val extractedUrl = extractUrl(message)
        val hasUrl = !extractedUrl.isNullOrBlank()
        val isShortened = containsShortenedUrl(message) || (extractedUrl != null && isShortenedUrl(extractedUrl))

        val matchedBanks = findMatchedBanks(message)
        val matchedTelcos = findMatchedTelcos(message)
        val matchedUrgency = findMatchedUrgency(message)
        val matchedCtas = findMatchedCta(message)

        if (isShortened) {
            val shortUrlDisplay = extractedUrl ?: "detected short link"
            val explanation = "Flagged as Harmful: This message contains a shortened URL ($shortUrlDisplay). $SHORTENED_URL_REASONING"
            return DetectionResult(
                sender = "Unknown",
                message = message,
                classification = Classification.SMISHING,
                probability = 0.95f,
                isScanning = false,
                urlFound = true,
                extractedUrl = extractedUrl,
                urlScore = 1.0f,
                urlVerdict = "malicious",
                urlContributions = listOf("Shortened URL Detected"),
                explanation = SHORTENED_URL_REASONING,
                overallExplanation = explanation,
                localVerdict = "Harmful",
                ensembleFormula = "Rule-based: Shortened URL (Harmful)",
                rfProb = 0.95f,
                rfRawLogit = 0f,
                xgbProb = 0.95f,
                cnnProb = null
            )
        }

        var score = 0.05f // baseline safe score

        if (hasUrl) {
            score += 0.25f
            val urlLower = extractedUrl?.lowercase() ?: ""
            if (URL_SHORTENERS.any { urlLower.contains(it) }) score += 0.20f
            if (Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}").containsMatchIn(urlLower)) score += 0.30f
            if (listOf(".cc", ".xyz", ".top", ".icu", ".click", ".tk").any { urlLower.contains(it) }) score += 0.25f

            if (matchedBanks.isNotEmpty()) {
                score += 0.40f // Bank reference + link = critical smishing indicator
            }
            if (matchedUrgency.isNotEmpty()) {
                score += 0.25f
            }
            if (matchedCtas.isNotEmpty()) {
                score += 0.20f
            }
        } else {
            // No URL in message
            if (matchedBanks.isNotEmpty() && (matchedUrgency.isNotEmpty() || matchedCtas.isNotEmpty())) {
                score += 0.45f
            } else if (matchedUrgency.isNotEmpty()) {
                score += 0.25f
            }
            if (matchedCtas.isNotEmpty()) {
                score += 0.20f
            }
            if (matchedTelcos.isNotEmpty()) {
                score += 0.10f
            }
        }

        val upperRatio = if (message.isNotEmpty()) message.count { it.isUpperCase() }.toFloat() / message.length else 0f
        if (upperRatio > 0.35f) score += 0.10f

        val finalProb = score.coerceIn(0.02f, 0.98f)

        val classification = when {
            finalProb >= smishingThreshold -> Classification.SMISHING
            finalProb >= suspiciousThreshold -> Classification.SUSPICIOUS
            else -> Classification.SAFE
        }

        val triggers = mutableListOf<String>()
        if (hasUrl) triggers.add("unverified link")
        if (matchedBanks.isNotEmpty()) triggers.add("financial institution reference (${matchedBanks.joinToString()})")
        if (matchedUrgency.isNotEmpty()) triggers.add("high-pressure urgency phrasing")
        if (matchedCtas.isNotEmpty()) triggers.add("action prompts")

        val triggerStr = if (triggers.isNotEmpty()) triggers.joinToString(", ") else "benign text pattern"
        val explanation = when (classification) {
            Classification.SMISHING -> "Flagged as Harmful by local security heuristics due to $triggerStr."
            Classification.SUSPICIOUS -> "Flagged as Suspicious by local security heuristics due to $triggerStr."
            Classification.SAFE -> "No suspicious patterns detected in message text by local security heuristics."
        }

        return DetectionResult(
            sender = "Unknown",
            message = message,
            classification = classification,
            probability = finalProb,
            isScanning = false,
            overallExplanation = explanation,
            rfProb = finalProb,
            rfRawLogit = 0f,
            xgbProb = finalProb,
            cnnProb = null
        )
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

    fun extractUrl(text: String): String? {
        // Priority 1: Explicit scheme match (http:// or https://)
        val schemeMatch = Regex("https?://\\S+", RegexOption.IGNORE_CASE).find(text)?.value
        if (schemeMatch != null) {
            return schemeMatch.trimEnd('.', ',', ')', ']', '!', '?', '"', '\'')
        }
        // Priority 2: www-prefixed match
        val wwwMatch = Regex("www\\.\\S+", RegexOption.IGNORE_CASE).find(text)?.value
        if (wwwMatch != null) {
            return wwwMatch.trimEnd('.', ',', ')', ']', '!', '?', '"', '\'')
        }
        // Priority 3: Fallback domain match (e.g. example.com/path)
        val domainMatch = Regex("[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}(/\\S*)?", RegexOption.IGNORE_CASE).find(text)?.value
        return domainMatch?.trimEnd('.', ',', ')', ']', '!', '?', '"', '\'')
    }

    fun hasUrl(text: String): Boolean {
        return extractUrl(text) != null
    }

    fun extractNumericalFeatures(text: String): FloatArray {
        val extractedUrl = extractUrl(text)
        val urls = if (extractedUrl != null) listOf(extractedUrl) else emptyList()
        val urlPresent = if (urls.isNotEmpty()) 1.0f else 0.0f
        val urlCount = urls.size.toFloat()
        var hasShortener = 0.0f
        var hasHttps = 0.0f
        var domainLength = 0.0f
        var subdomainCount = 0.0f
        var hasIp = 0.0f
        var pathDepth = 0.0f
        var urlSpecialChars = 0.0f
        var hasSuspiciousTld = 0.0f
        var hasDeceptive = 0.0f

        if (urls.isNotEmpty()) {
            val url = urls[0].lowercase()
            hasShortener = if (URL_SHORTENERS.any { url.contains(it) }) 1.0f else 0.0f
            hasHttps = if (url.startsWith("https")) 1.0f else 0.0f
            
            val domainMatch = Regex("https?://([^/]+)").find(url)
            val domain = domainMatch?.groupValues?.get(1) ?: url.split("/")[0]
            domainLength = domain.length.toFloat()
            subdomainCount = domain.count { it == '.' }.minus(1).coerceAtLeast(0).toFloat()
            hasIp = if (Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}").containsMatchIn(domain)) 1.0f else 0.0f
            
            val path = url.replace(domainMatch?.value ?: "", "")
            pathDepth = path.split("/").filter { it.isNotEmpty() }.size.toFloat()
            urlSpecialChars = url.count { it in "-_~%@" }.toFloat()

            val suspiciousTlds = listOf(".cc", ".xyz", ".top", ".icu")
            hasSuspiciousTld = if (suspiciousTlds.any { domain.endsWith(it) }) 1.0f else 0.0f

            val domainParts = domain.split(".")
            if (domainParts.size > 2) {
                val subLevels = domainParts.dropLast(2)
                hasDeceptive = if (subLevels.any { it == "gov" || it == "com" || it == "edu" }) 1.0f else 0.0f
            }
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
            urlPresent, urlCount, hasShortener, hasHttps, domainLength, subdomainCount, hasIp, pathDepth, urlSpecialChars, hasSuspiciousTld, hasDeceptive,
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

        // If models completely failed to initialize, use rule-based heuristic classifier
        if (env == null || (rfSession == null && xgbSession == null) || tfidfSession == null || scalerSession == null) {
            return classifyWithHeuristics(message)
        }

        return try {
            val cleaned = cleanText(message)
            val prep = preprocessText(cleaned)
            val rawNum = extractNumericalFeatures(message)

            val envLocal = env ?: return classifyWithHeuristics(message)

            // 1. Scale numerical features (21 dimensions)
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

            // 3. Concatenate (1500 text features + 21 numerical features = 1521)
            val combinedInput = FloatArray(TOTAL_FEATURES_COUNT)
            System.arraycopy(textTfidf, 0, combinedInput, 0, TFIDF_FEATURES_COUNT)
            System.arraycopy(scaledNum, 0, combinedInput, TFIDF_FEATURES_COUNT, NUMERICAL_FEATURES_COUNT)

            // 4. Run Classifier inference (1521 dimensions)
            val combinedInputTensor = OnnxTensor.createTensor(
                envLocal,
                FloatBuffer.wrap(combinedInput),
                longArrayOf(1L, TOTAL_FEATURES_COUNT.toLong())
            )
            var rfProb = 0.0f
            var rfRawLogit = 0.0f
            var xgbProb = 0.0f

            combinedInputTensor.use { tensor ->
                // Random Forest expects input named "features"
                if (rfSession != null) {
                    try {
                        val rfResult = rfSession?.run(mapOf("features" to tensor))
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
                    } catch (t: Throwable) {
                        android.util.Log.w("LocalClassifier", "RF inference failed", t)
                    }
                }

                // XGBoost expects input named "features"
                if (xgbSession != null) {
                    try {
                        val xgbResult = xgbSession?.run(mapOf("features" to tensor))
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
                    } catch (t: Throwable) {
                        android.util.Log.w("LocalClassifier", "XGB inference failed", t)
                    }
                }
            }

            // If one model failed or wasn't loaded, adapt weights gracefully
            val localProb = when {
                rfSession != null && xgbSession != null && (rfProb > 0f || xgbProb > 0f) -> {
                    rfWeight * rfProb + xgbWeight * xgbProb
                }
                xgbSession != null && xgbProb > 0f -> {
                    rfProb = xgbProb // Mirror for UI display
                    xgbProb
                }
                rfSession != null && rfProb > 0f -> {
                    xgbProb = rfProb // Mirror for UI display
                    rfProb
                }
                else -> {
                    // Both models returned 0.0 or failed during inference
                    val heuristicRes = classifyWithHeuristics(message)
                    rfProb = heuristicRes.rfProb
                    xgbProb = heuristicRes.xgbProb
                    heuristicRes.probability
                }
            }

            val extractedUrl = extractUrl(message)
            val hasUrl = !extractedUrl.isNullOrBlank()
            val isShortened = containsShortenedUrl(message) || (extractedUrl != null && isShortenedUrl(extractedUrl))

            val classification = if (isShortened) Classification.SMISHING else when {
                localProb >= smishingThreshold -> Classification.SMISHING
                localProb >= suspiciousThreshold -> Classification.SUSPICIOUS
                else -> Classification.SAFE
            }
            val finalProb = if (isShortened) maxOf(localProb, 0.95f) else localProb

            val explanation = generateHumanReadableExplanation(message, classification)

            DetectionResult(
                sender = "Unknown",
                message = message,
                classification = classification,
                probability = finalProb,
                isScanning = false,
                urlFound = hasUrl || isShortened,
                extractedUrl = extractedUrl,
                urlScore = if (isShortened) 1.0f else null,
                urlVerdict = if (isShortened) "malicious" else null,
                urlContributions = if (isShortened) listOf("Shortened URL Detected") else null,
                explanation = if (isShortened) SHORTENED_URL_REASONING else null,
                overallExplanation = explanation,
                localVerdict = if (isShortened) "Harmful" else classification.name.lowercase().replaceFirstChar { it.uppercase() },
                ensembleFormula = if (isShortened) "Rule-based: Shortened URL (Harmful) + Local ML" else null,
                rfProb = if (isShortened) maxOf(rfProb, 0.95f) else rfProb,
                rfRawLogit = rfRawLogit,
                xgbProb = if (isShortened) maxOf(xgbProb, 0.95f) else xgbProb,
                cnnProb = null
            )
        } catch (t: Throwable) {
            android.util.Log.e("LocalClassifier", "Error during inference, falling back to heuristics", t)
            classifyWithHeuristics(message)
        }
    }

    fun generateHumanReadableExplanation(message: String, classification: Classification): String {
        val textLower = message.lowercase()
        val extractedUrl = extractUrl(message)
        val isShortened = containsShortenedUrl(message) || (extractedUrl != null && isShortenedUrl(extractedUrl))

        if (isShortened) {
            val shortUrlDisplay = extractedUrl ?: "detected short link"
            return "Flagged as Harmful: This message contains a shortened URL ($shortUrlDisplay). $SHORTENED_URL_REASONING"
        }

        val hasCta = CTA_PHRASES.any { textLower.contains(it) }
        val hasBank = PH_BANKS.any { textLower.contains(it) }
        val hasTelco = PH_TELCOS.any { textLower.contains(it) }
        val hasUrgency = PH_URGENCY.any { textLower.contains(it) }

        val detectedTriggers = mutableListOf<String>()

        val hasUrl = !extractedUrl.isNullOrBlank()

        if (hasUrl) {
            detectedTriggers.add("an unverified web link")
        }
        if (hasUrgency) {
            detectedTriggers.add("high-pressure urgency phrasing or reward claims")
        }
        if (hasBank) {
            detectedTriggers.add("references to financial institutions or e-wallet services")
        }
        if (hasTelco) {
            detectedTriggers.add("telecom carrier promo/account references")
        }
        if (hasCta) {
            detectedTriggers.add("action-oriented prompt keywords (e.g. click, verify, claim)")
        }

        val baseExplanation = when (classification) {
            Classification.SMISHING -> {
                if (detectedTriggers.isNotEmpty()) {
                    "Local AI models flagged this message as Harmful because it contains " +
                            detectedTriggers.joinToString(", ") + "."
                } else {
                    "Local AI models flagged this message as Harmful due to detected scam patterns in text structure and vocabulary."
                }
            }
            Classification.SUSPICIOUS -> {
                if (detectedTriggers.isNotEmpty()) {
                    "Local AI models flagged this message as Suspicious because it contains " +
                            detectedTriggers.joinToString(", ") + "."
                } else {
                    "Local AI models flagged this message as Suspicious due to promotional or unsolicited text characteristics."
                }
            }
            Classification.SAFE -> {
                if (hasUrl) {
                    "No immediate suspicious text patterns were detected in this message by local AI models."
                } else {
                    "No suspicious patterns, urgency triggers, or malicious links were detected in this message by local AI models."
                }
            }
        }

        return if (hasUrl && !baseExplanation.contains("cannot be guaranteed", ignoreCase = true) && !baseExplanation.contains("no online threat scan", ignoreCase = true)) {
            "$baseExplanation Exercise caution: this message contains an unverified web link that has not been scanned by online threat intelligence yet, so its safety cannot be guaranteed."
        } else {
            baseExplanation
        }
    }

    fun formatDetailsExplanation(result: DetectionResult): String {
        val rfProbStr = String.format(java.util.Locale.US, "%.1f%%", result.rfProb * 100)
        val xgbProbStr = String.format(java.util.Locale.US, "%.1f%%", result.xgbProb * 100)
        val localEnsembleScore = rfWeight * result.rfProb + xgbWeight * result.xgbProb
        val localScoreStr = String.format(java.util.Locale.US, "%.1f%%", localEnsembleScore * 100)
        val localVerdictStr = result.localVerdict ?: result.classification.name.lowercase().replaceFirstChar { it.uppercase() }

        val cnnScoreVal = result.cnnScore ?: result.cnnProb
        val cnnScoreStr = if (cnnScoreVal != null) String.format(java.util.Locale.US, "%.1f%%", cnnScoreVal * 100) else "N/A"
        val cnnVerdictStr = result.cnnVerdict ?: "N/A"

        val hasUrl = result.urlFound
        val urlScoreStr = if (result.urlScore != null) String.format(java.util.Locale.US, "%.1f%%", result.urlScore * 100) else "N/A"
        val urlVerdictStr = result.urlVerdict ?: "N/A"

        val formulaHeader = result.ensembleFormula ?: if (hasUrl) "Ensemble (50% CNN + 25% URL + 25% Local)" else "Ensemble (66.7% CNN + 33.3% Local)"

        return if (hasUrl) {
            "Ensemble Calculation Breakdown:\n" +
            "Formula: $formulaHeader\n\n" +
            "  • Local Classifier (RF+XGB): $localScoreStr ($localVerdictStr)\n" +
            "      └ RF: $rfProbStr | XGB: $xgbProbStr\n" +
            "  • CNN-BiGRU API:             $cnnScoreStr ($cnnVerdictStr)\n" +
            "  • URL Analysis:               $urlScoreStr ($urlVerdictStr)"
        } else {
            "Ensemble Calculation Breakdown:\n" +
            "Formula: $formulaHeader\n\n" +
            "  • Local Classifier (RF+XGB): $localScoreStr ($localVerdictStr)\n" +
            "      └ RF: $rfProbStr | XGB: $xgbProbStr\n" +
            "  • CNN-BiGRU API:             $cnnScoreStr ($cnnVerdictStr)\n" +
            "  • URL Analysis:               No URL detected"
        }
    }
}
