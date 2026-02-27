package com.example

import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import com.assistant.ports.CommandSpec
import com.assistant.ports.ParamSpec
import com.assistant.ports.ToolPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder

class WeatherTool(
    private val baseUrl: String = "https://wttr.in",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()
) : ToolPort {
    override val name = "weather"
    override val description = "Returns current weather conditions for a given city"

    override fun commands() = listOf(
        CommandSpec(
            name = "weather_current",
            description = "Get current weather for a city",
            params = listOf(
                ParamSpec(name = "city", type = "string", description = "The city name to get weather for")
            )
        )
    )

    override suspend fun execute(call: ToolCall): Observation {
        if (call.name != "weather_current") return Observation.Error("Unknown command: ${call.name}")

        val city = call.arguments["city"]?.toString()
            ?: return Observation.Error("Missing required parameter: city")

        val encodedCity = URLEncoder.encode(city, Charsets.UTF_8)
        val url = "$baseUrl/$encodedCity?format=3"

        val request = Request.Builder()
            .url(url)
            .build()

        return withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body == null) {
                            Observation.Error("Empty response body")
                        } else {
                            Observation.Success(body)
                        }
                    } else {
                        Observation.Error("HTTP ${response.code}: ${response.message}")
                    }
                }
            } catch (e: Exception) {
                Observation.Error(e.message ?: "Network error")
            }
        }
    }
}
