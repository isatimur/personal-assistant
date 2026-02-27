package com.assistant.tools.http

import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import com.assistant.ports.CommandSpec
import com.assistant.ports.ParamSpec
import com.assistant.ports.ToolPort
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class HttpTool : ToolPort {
    override val name = "http"
    override val description = "Make HTTP requests to any REST API. Commands: http_get, http_post, http_put, http_delete"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    override fun commands(): List<CommandSpec> = listOf(
        CommandSpec(
            name = "http_get",
            description = "HTTP GET request to a URL",
            params = listOf(
                ParamSpec("url", "string", "The URL to request"),
                ParamSpec("headers", "string", "JSON object of request headers", required = false)
            )
        ),
        CommandSpec(
            name = "http_post",
            description = "HTTP POST request with a body",
            params = listOf(
                ParamSpec("url", "string", "The URL to request"),
                ParamSpec("body", "string", "Request body (JSON string or plain text)"),
                ParamSpec("headers", "string", "JSON object of request headers", required = false)
            )
        ),
        CommandSpec(
            name = "http_put",
            description = "HTTP PUT request with a body",
            params = listOf(
                ParamSpec("url", "string", "The URL to request"),
                ParamSpec("body", "string", "Request body (JSON string or plain text)"),
                ParamSpec("headers", "string", "JSON object of request headers", required = false)
            )
        ),
        CommandSpec(
            name = "http_delete",
            description = "HTTP DELETE request",
            params = listOf(
                ParamSpec("url", "string", "The URL to request"),
                ParamSpec("headers", "string", "JSON object of request headers", required = false)
            )
        )
    )

    override suspend fun execute(call: ToolCall): Observation {
        val url = call.arguments["url"] as? String
            ?: return Observation.Error("Missing required parameter: url")
        val headersJson = call.arguments["headers"] as? String
        val body = call.arguments["body"] as? String

        if (call.name in listOf("http_post", "http_put") && body == null) {
            return Observation.Error("Missing required parameter: body")
        }

        return runCatching {
            val reqBuilder = Request.Builder().url(url)
            val headerError = applyHeaders(reqBuilder, headersJson)
            if (headerError != null) return headerError

            val request = when (call.name) {
                "http_get" -> reqBuilder.get().build()
                "http_post" -> reqBuilder.post((body ?: "").toRequestBody(contentType(headersJson))).build()
                "http_put" -> reqBuilder.put((body ?: "").toRequestBody(contentType(headersJson))).build()
                "http_delete" -> reqBuilder.delete().build()
                else -> return Observation.Error("Unknown http command: ${call.name}")
            }

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                Observation.Success("HTTP ${response.code}\n$responseBody")
            }
        }.getOrElse { Observation.Error(it.message ?: "HTTP request failed") }
    }

    private fun applyHeaders(builder: Request.Builder, headersJson: String?): Observation.Error? {
        if (headersJson.isNullOrBlank()) return null
        return runCatching {
            val obj = json.parseToJsonElement(headersJson) as? JsonObject
                ?: return Observation.Error("headers must be a JSON object")
            obj.forEach { (key, value) -> builder.header(key, value.jsonPrimitive.content) }
            null
        }.getOrElse { Observation.Error("Invalid headers JSON: ${it.message}") }
    }

    private fun contentType(headersJson: String?): okhttp3.MediaType {
        if (!headersJson.isNullOrBlank()) {
            runCatching {
                val obj = json.parseToJsonElement(headersJson) as? JsonObject
                val ct = obj?.get("Content-Type")?.jsonPrimitive?.content
                if (ct != null) return ct.toMediaType()
            }
        }
        return "application/json".toMediaType()
    }
}
