package com.assistant.tools.web

import com.assistant.domain.*
import com.assistant.ports.CommandSpec
import com.assistant.ports.ParamSpec
import com.assistant.ports.ToolPort
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class WebBrowserTool(private val maxContentChars: Int = 8_000) : ToolPort {
    override val name = "web"
    override val description = "Fetches web pages and searches. Commands: web_fetch(url), web_search(query)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun commands(): List<CommandSpec> = listOf(
        CommandSpec(
            name = "web_fetch",
            description = "Fetch and extract text content from a URL",
            params = listOf(
                ParamSpec("url", "string", "The URL to fetch")
            )
        ),
        CommandSpec(
            name = "web_search",
            description = "Search the web using DuckDuckGo and return results",
            params = listOf(
                ParamSpec("query", "string", "The search query")
            )
        )
    )

    override suspend fun execute(call: ToolCall): Observation {
        return when (call.name) {
            "web_fetch" -> {
                val url = call.arguments["url"] as? String ?: return Observation.Error("Missing 'url'")
                fetchUrl(url)
            }
            "web_search" -> {
                val query = call.arguments["query"] as? String ?: return Observation.Error("Missing 'query'")
                search(query)
            }
            else -> Observation.Error("Unknown web command: ${call.name}")
        }
    }

    private fun fetchUrl(url: String): Observation = runCatching {
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        val body = client.newCall(req).execute().body?.string()
            ?: return@runCatching Observation.Error("Empty response")
        val text = Jsoup.parse(body).text()
        Observation.Success(if (text.length > maxContentChars) text.take(maxContentChars) + "..." else text)
    }.getOrElse { Observation.Error(it.message ?: "Fetch failed") }

    private fun search(query: String): Observation = runCatching {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val req = Request.Builder()
            .url("https://html.duckduckgo.com/html/?q=$encoded")
            .header("User-Agent", "Mozilla/5.0").build()
        val body = client.newCall(req).execute().body?.string()
            ?: return@runCatching Observation.Error("Empty response")
        val results = Jsoup.parse(body).select(".result__title, .result__snippet")
            .take(10).joinToString("\n") { it.text() }
        Observation.Success(results.ifBlank { "No results found" })
    }.getOrElse { Observation.Error(it.message ?: "Search failed") }
}
