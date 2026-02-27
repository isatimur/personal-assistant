package com.example

import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CalculatorToolTest {

    private val tool = CalculatorTool()

    @Test
    fun `2+2 evaluates to 4`() = runTest {
        val result = tool.execute(ToolCall("calculator_evaluate", mapOf("expression" to "2+2")))
        assertEquals(Observation.Success("4"), result)
    }

    @Test
    fun `(3+4) times 2 evaluates to 14`() = runTest {
        val result = tool.execute(ToolCall("calculator_evaluate", mapOf("expression" to "(3+4)*2")))
        assertEquals(Observation.Success("14"), result)
    }

    @Test
    fun `10 divided by 4 evaluates to 2 point 5`() = runTest {
        val result = tool.execute(ToolCall("calculator_evaluate", mapOf("expression" to "10 / 4")))
        assertEquals(Observation.Success("2.5"), result)
    }

    @Test
    fun `division by zero returns Error`() = runTest {
        val result = tool.execute(ToolCall("calculator_evaluate", mapOf("expression" to "5/0")))
        assertTrue(result is Observation.Error)
        assertEquals("Division by zero", (result as Observation.Error).message)
    }

    @Test
    fun `invalid expression returns Error`() = runTest {
        val result = tool.execute(ToolCall("calculator_evaluate", mapOf("expression" to "2 + abc")))
        assertTrue(result is Observation.Error)
        val error = result as Observation.Error
        assertTrue(error.message.isNotEmpty(), "Error message should not be empty")
    }

    @Test
    fun `unary minus evaluates correctly`() = runTest {
        val result = tool.execute(ToolCall("calculator_evaluate", mapOf("expression" to "-3")))
        assertEquals(Observation.Success("-3"), result)
    }

    @Test
    fun `trailing garbage returns Error`() = runTest {
        val result = tool.execute(ToolCall("calculator_evaluate", mapOf("expression" to "2+2abc")))
        assertTrue(result is Observation.Error)
    }

    @Test
    fun `unknown command returns Error`() = runTest {
        val result = tool.execute(ToolCall("calculator_unknown", mapOf("expression" to "1+1")))
        assertTrue(result is Observation.Error)
    }

    @Test
    fun `whitespace is handled`() = runTest {
        val result = tool.execute(ToolCall("calculator_evaluate", mapOf("expression" to " 3 + 4 ")))
        assertEquals(Observation.Success("7"), result)
    }
}
