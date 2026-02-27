package com.assistant.tools.web

import com.assistant.domain.*
import com.assistant.ports.CommandSpec
import com.assistant.ports.ParamSpec
import com.assistant.ports.ToolPort
import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.io.Closeable
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class WebBrowserTool(
    private val maxContentChars: Int = 8_000,
    private val searchProvider: String = "duckduckgo",
    private val searchApiKey: String = "",
    private val searchBaseUrl: String = "https://api.search.brave.com"
) : ToolPort, Closeable {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
    }

    private val _playwright = lazy { Playwright.create() }
    private val _browser = lazy {
        _playwright.value.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
    }
    private val playwright: Playwright by _playwright
    private val browser: Browser by _browser
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
            description = "Search the web and return results",
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

    private suspend fun fetchUrl(url: String): Observation = withContext(Dispatchers.IO) {
        runCatching {
            browser.newPage().use { page ->
                page.navigate(url, Page.NavigateOptions().setTimeout(15_000.0))
                page.waitForLoadState(
                    com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                    Page.WaitForLoadStateOptions().setTimeout(15_000.0)
                )
                val html = page.content()
                val text = Jsoup.parse(html).text()
                Observation.Success(if (text.length > maxContentChars) text.take(maxContentChars) + "..." else text)
            }
        }.getOrElse { Observation.Error(it.message ?: "Fetch failed") }
    }

    private fun search(query: String): Observation = when (searchProvider.lowercase()) {
        "brave" -> searchBrave(query)
        "tavily" -> searchTavily(query)
        else -> searchDuckduckgo(query)
    }

    private fun searchDuckduckgo(query: String): Observation = runCatching {
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

    private fun searchBrave(query: String): Observation = runCatching {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "${searchBaseUrl.trimEnd('/')}/res/v1/web/search?q=$encoded&count=10"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Subscription-Token", searchApiKey)
            .build()
        val body = client.newCall(req).execute().body?.string()
            ?: return@runCatching Observation.Error("Empty response from Brave")
        val root = json.parseToJsonElement(body).jsonObject
        val results = root["web"]?.jsonObject?.get("results")?.jsonArray
            ?: return@runCatching Observation.Error("No results")
        val text = results.take(10).joinToString("\n") { item ->
            val o = item.jsonObject
            val title = o["title"]?.jsonPrimitive?.content ?: ""
            val desc = o["description"]?.jsonPrimitive?.content ?: ""
            val href = o["url"]?.jsonPrimitive?.content ?: ""
            "$title\n$desc\n$href"
        }
        Observation.Success(text.ifBlank { "No results found" })
    }.getOrElse { Observation.Error(it.message ?: "Brave search failed") }

    private fun searchTavily(query: String): Observation = runCatching {
        val bodyJson = buildJsonObject {
            put("api_key", searchApiKey)
            put("query", query)
            put("search_depth", "basic")
            put("max_results", 10)
        }.toString()
        val req = Request.Builder()
            .url("https://api.tavily.com/search")
            .header("Content-Type", "application/json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()
        val body = client.newCall(req).execute().body?.string()
            ?: return@runCatching Observation.Error("Empty response from Tavily")
        val root = json.parseToJsonElement(body).jsonObject
        val results = root["results"]?.jsonArray
            ?: return@runCatching Observation.Error("No results")
        val text = results.take(10).joinToString("\n") { item ->
            val o = item.jsonObject
            val title = o["title"]?.jsonPrimitive?.content ?: ""
            val content = o["content"]?.jsonPrimitive?.content ?: ""
            val href = o["url"]?.jsonPrimitive?.content ?: ""
            "$title\n$content\n$href"
        }
        Observation.Success(text.ifBlank { "No results found" })
    }.getOrElse { Observation.Error(it.message ?: "Tavily search failed") }

    override fun close() {
        if (_browser.isInitialized()) runCatching { browser.close() }
        if (_playwright.isInitialized()) runCatching { playwright.close() }
    }
}
