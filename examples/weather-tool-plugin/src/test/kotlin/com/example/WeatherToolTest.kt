package com.example

import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WeatherToolTest {

    private val server = MockWebServer()

    @BeforeEach
    fun setUp() {
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun tool() = WeatherTool(server.url("/").toString().trimEnd('/'))

    @Test
    fun `weather_current returns city weather`() = runTest {
        server.enqueue(MockResponse().setBody("London: 15°C"))

        val result = tool().execute(ToolCall("weather_current", mapOf("city" to "London")))

        assertTrue(result is Observation.Success, "Expected Success but got: $result")
        val text = (result as Observation.Success).result
        assertTrue(text.contains("London"), "Expected response to contain 'London', got: $text")
    }

    @Test
    fun `HTTP error returns Observation Error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        val result = tool().execute(ToolCall("weather_current", mapOf("city" to "London")))

        assertTrue(result is Observation.Error, "Expected Error but got: $result")
        val message = (result as Observation.Error).message
        assertTrue(message.contains("500"), "Expected error message to contain '500', got: $message")
    }

    @Test
    fun `unknown command returns Observation Error`() = runTest {
        val result = tool().execute(ToolCall("weather_unknown", mapOf("city" to "London")))

        assertTrue(result is Observation.Error, "Expected Error but got: $result")
    }
}
