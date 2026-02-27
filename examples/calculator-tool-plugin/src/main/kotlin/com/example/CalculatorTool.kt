package com.example

import com.assistant.domain.Observation
import com.assistant.domain.ToolCall
import com.assistant.ports.CommandSpec
import com.assistant.ports.ParamSpec
import com.assistant.ports.ToolPort

class CalculatorTool : ToolPort {
    override val name = "calculator"
    override val description = "Evaluates arithmetic expressions with +, -, *, / and parentheses"

    override fun commands() = listOf(
        CommandSpec(
            name = "calculator_evaluate",
            description = "Evaluate an arithmetic expression and return the result",
            params = listOf(
                ParamSpec(
                    name = "expression",
                    type = "string",
                    description = "The arithmetic expression to evaluate, e.g. '2 + 3 * 4'"
                )
            )
        )
    )

    override suspend fun execute(call: ToolCall): Observation {
        if (call.name != "calculator_evaluate") return Observation.Error("Unknown command: ${call.name}")
        val expression = call.arguments["expression"]?.toString()
            ?: return Observation.Error("Missing required parameter: expression")
        return try {
            val parser = Parser(expression)
            val result = parser.parseExpr()
            if (!parser.isExhausted()) throw IllegalArgumentException("Unexpected trailing input at position ${parser.pos}")
            val formatted = if (result % 1.0 == 0.0) result.toLong().toString() else result.toString()
            Observation.Success(formatted)
        } catch (e: ArithmeticException) {
            Observation.Error(e.message ?: "Arithmetic error")
        } catch (e: IllegalArgumentException) {
            Observation.Error(e.message ?: "Invalid expression")
        }
    }

    private class Parser(private val input: String) {
        var pos = 0
            private set

        fun isExhausted(): Boolean {
            skipWhitespace()
            return pos >= input.length
        }

        fun parseExpr(): Double {
            var result = parseTerm()
            while (true) {
                skipWhitespace()
                if (pos >= input.length) break
                when (input[pos]) {
                    '+' -> { pos++; result += parseTerm() }
                    '-' -> { pos++; result -= parseTerm() }
                    else -> break
                }
            }
            return result
        }

        private fun parseTerm(): Double {
            var result = parseFactor()
            while (true) {
                skipWhitespace()
                if (pos >= input.length) break
                when (input[pos]) {
                    '*' -> { pos++; result *= parseFactor() }
                    '/' -> {
                        pos++
                        val divisor = parseFactor()
                        if (divisor == 0.0) throw ArithmeticException("Division by zero")
                        result /= divisor
                    }
                    else -> break
                }
            }
            return result
        }

        private fun parseFactor(): Double {
            skipWhitespace()
            if (pos >= input.length) throw IllegalArgumentException("Unexpected end of expression")
            return when {
                input[pos] == '(' -> {
                    pos++
                    val result = parseExpr()
                    skipWhitespace()
                    if (pos >= input.length || input[pos] != ')')
                        throw IllegalArgumentException("Expected closing parenthesis")
                    pos++
                    result
                }
                input[pos] == '-' -> { pos++; -parseFactor() }
                input[pos].isDigit() || input[pos] == '.' -> parseNumber()
                else -> throw IllegalArgumentException("Unexpected token '${input[pos]}' at position $pos")
            }
        }

        private fun parseNumber(): Double {
            val start = pos
            while (pos < input.length && (input[pos].isDigit() || input[pos] == '.')) pos++
            val token = input.substring(start, pos)
            return token.toDoubleOrNull()
                ?: throw IllegalArgumentException("Invalid number: $token")
        }

        private fun skipWhitespace() {
            while (pos < input.length && input[pos].isWhitespace()) pos++
        }
    }
}
