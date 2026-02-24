package com.assistant.agent

import com.assistant.domain.*
import com.assistant.ports.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CompactionServiceTest {
    private val llm = mockk<LlmPort>()
    private val memory = mockk<MemoryPort>()

    @Test
    fun `does not compact when history is below threshold`() = runTest {
        val svc = CompactionService(llm, memory, threshold = 15)
        coEvery { memory.history(any(), any()) } returns List(10) {
            Message("user", "msg$it", Channel.TELEGRAM)
        }
        svc.maybeCompact("s1", "user1")
        coVerify(exactly = 0) { llm.complete(any()) }
    }

    @Test
    fun `compacts when history meets threshold`() = runTest {
        val svc = CompactionService(llm, memory, threshold = 15)
        val oldMessages = List(15) { Message("user", "old msg $it", Channel.TELEGRAM) }
        coEvery { memory.history("s1", 100) } returns oldMessages
        coEvery { llm.complete(any()) } returns "- User is a Kotlin developer\n- User is building an AI assistant"
        coEvery { memory.saveFact(any(), any()) } just runs
        coEvery { memory.trimHistory(any(), any()) } just runs

        svc.maybeCompact("s1", "user1")

        coVerify { memory.saveFact("user1", "User is a Kotlin developer") }
        coVerify { memory.saveFact("user1", "User is building an AI assistant") }
        coVerify { memory.trimHistory("s1", 10) }
    }

    @Test
    fun `handles blank LLM response gracefully`() = runTest {
        val svc = CompactionService(llm, memory, threshold = 15)
        val oldMessages = List(15) { Message("user", "msg$it", Channel.TELEGRAM) }
        coEvery { memory.history("s1", 100) } returns oldMessages
        coEvery { llm.complete(any()) } returns "   "
        coEvery { memory.trimHistory(any(), any()) } just runs

        svc.maybeCompact("s1", "user1")  // must not throw

        coVerify(exactly = 0) { memory.saveFact(any(), any()) }
        coVerify { memory.trimHistory("s1", 10) }
    }
}
