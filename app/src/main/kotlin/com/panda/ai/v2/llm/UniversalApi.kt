package com.panda.ai.v2.llm

import android.content.Context
import android.util.Log
import com.panda.ai.utilities.ApiKeyManager
import com.panda.ai.v2.AgentOutput
import com.panda.ai.v2.logging.TaskLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Which LLM provider a pasted key belongs to. Detected automatically from
 * the key's prefix - no manual provider selection needed.
 */
enum class LlmProvider(val displayName: String, val baseUrl: String) {
    GEMINI("Gemini", "https://generativelanguage.googleapis.com/v1beta/openai"),
    NVIDIA("NVIDIA NIM", "https://integrate.api.nvidia.com/v1")
}

data class ModelInfo(val id: String, val isFree: Boolean)

object ProviderDetector {
    /** Returns null if the key doesn't match any supported provider's format. */
    fun detect(apiKey: String): LlmProvider? {
        val key = apiKey.trim()
        return when {
            key.startsWith("nvapi-") -> LlmProvider.NVIDIA
            key.startsWith("AIza") -> LlmProvider.GEMINI
            else -> null
        }
    }
}

// Chat-incapable NIM catalog entries (embeddings, vision-only, speech, safety
// filters, etc.) that would break our text-only JSON agent loop if selected.
private val NVIDIA_EXCLUDE_SUBSTRINGS = listOf(
    "embed", "rerank", "guard", "safety", "vision", "asr", "tts",
    "riva", "retriever", "parakeet", "fastconformer", "ocr", "moderation"
)

// Gemini model ids that aren't usable chat/completions models.
private val GEMINI_EXCLUDE_SUBSTRINGS = listOf(
    "embedding", "aqa", "imagen", "veo", "tts", "gemini-1.0", "vision"
)

class UniversalApi(
    private val context: Context,
    private val maxRetry: Int = 10
) {
    companion object {
        private const val TAG = "UniversalLlmApi"
    }

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Live-fetches the model catalog for [provider] using [apiKey]. */
    suspend fun fetchModels(apiKey: String, provider: LlmProvider): List<ModelInfo> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("${provider.baseUrl}/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw Exception("${provider.displayName} error: HTTP ${response.code} - $responseBody")
                }

                val root = jsonParser.parseToJsonElement(responseBody).jsonObject
                val dataArray = (root["data"] as? JsonArray) ?: return@use emptyList()

                dataArray.mapNotNull { element ->
                    val rawId = element.jsonObject["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val id = rawId.removePrefix("models/")
                    val idLower = id.lowercase()

                    when (provider) {
                        LlmProvider.NVIDIA -> {
                            if (NVIDIA_EXCLUDE_SUBSTRINGS.any { idLower.contains(it) }) return@mapNotNull null
                            // Whole NIM catalog is free on the dev/prototyping tier.
                            ModelInfo(id, isFree = true)
                        }
                        LlmProvider.GEMINI -> {
                            if (GEMINI_EXCLUDE_SUBSTRINGS.any { idLower.contains(it) }) return@mapNotNull null
                            // Flash / Flash-Lite have generous free-tier quotas;
                            // Pro-tier models are much more limited on free keys.
                            val isFree = idLower.contains("flash")
                            ModelInfo(id, isFree)
                        }
                    }
                }.sortedByDescending { it.isFree }
            }
        }

    suspend fun generateAgentOutput(messages: List<GeminiMessage>): AgentOutput? {
        val jsonString = retryWithBackoff(times = maxRetry) {
            performDirectApiCall(messages)
        } ?: return null

        try {
            val input = jsonParser.encodeToString(messages)
            TaskLogger.log(context, input, jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log task: ${e.message}")
        }

        return try {
            Log.d(TAG, "Parsing response: $jsonString")
            jsonParser.decodeFromString<AgentOutput>(jsonString)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON: ${e.message}", e)
            null
        }
    }

    private suspend fun performDirectApiCall(messages: List<GeminiMessage>): String =
        withContext(Dispatchers.IO) {
            val apiKey = ApiKeyManager.getApiKey(context)
            if (apiKey.isEmpty()) throw Exception("API key not set!")

            val provider = ProviderDetector.detect(apiKey)
                ?: throw Exception("Unrecognized API key format!")

            val modelId = ApiKeyManager.getSelectedModel(context)
            if (modelId.isEmpty()) throw Exception("No model selected!")

            val chatMessages = buildJsonArray {
                messages.forEachIndexed { index, message ->
                    // First message is always the system prompt (see
                    // MessageHistory.getMessages()). These OpenAI-compatible
                    // endpoints follow system-role instructions far more
                    // reliably than sending everything as "user".
                    val role = if (index == 0) "system" else "user"
                    val text = message.parts.joinToString("\n") { part ->
                        if (part is TextPart) part.text else ""
                    }
                    add(buildJsonObject {
                        put("role", role)
                        put("content", text)
                    })
                }
            }

            val requestBody = buildJsonObject {
                put("model", modelId)
                put("messages", chatMessages)
                put("response_format", buildJsonObject {
                    put("type", "json_object")
                })
                put("temperature", 0.4)
            }

            val body = requestBody.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("${provider.baseUrl}/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw Exception("${provider.displayName} error: HTTP ${response.code} - $responseBody")
                }

                extractContent(responseBody)
                    ?: throw Exception("${provider.displayName} returned no content: $responseBody")
            }
        }

    private fun extractContent(responseBody: String): String? {
        val root = jsonParser.parseToJsonElement(responseBody).jsonObject
        val choicesList = (root["choices"] as? JsonArray) ?: return null
        if (choicesList.isEmpty()) return null
        val firstMessage = choicesList[0].jsonObject["message"]?.jsonObject ?: return null
        return firstMessage["content"]?.jsonPrimitive?.content
    }
}

private suspend fun <T> retryWithBackoff(
    times: Int,
    initialDelay: Long = 500L,
    maxDelay: Long = 8000L,
    factor: Double = 2.0,
    block: suspend () -> T
): T? {
    var currentDelay = initialDelay
    repeat(times) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            Log.e("RetryUtil", "Attempt ${attempt + 1}/$times failed: ${e.message}")
            if (attempt == times - 1) return null
            delay(currentDelay)
            currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
        }
    }
    return null
}
