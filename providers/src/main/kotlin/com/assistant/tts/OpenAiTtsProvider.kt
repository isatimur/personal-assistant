package com.assistant.tts

import com.assistant.ports.TtsPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class OpenAiTtsProvider(
    private val apiKey: String,
    private val voice: String = "nova",
    private val model: String = "tts-1"
) : TtsPort {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun synthesize(text: String): ByteArray = withContext(Dispatchers.IO) {
        // Escape quotes in text for JSON safety
        val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"")
        val jsonBody = """{"model":"$model","input":"$escaped","voice":"$voice"}"""
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/speech")
            .header("Authorization", "Bearer $apiKey")
            .post(jsonBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("TTS API error: ${response.code} ${response.message}")
            }
            response.body?.bytes() ?: throw IllegalStateException("TTS API returned empty body")
        }
    }
}
