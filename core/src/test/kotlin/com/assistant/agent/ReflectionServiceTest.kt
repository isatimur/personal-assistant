package com.assistant.agent

import com.assistant.domain.Channel
import com.assistant.domain.Message
import com.assistant.ports.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.nio.file.Files

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReflectionServiceTest {
    private lateinit var llm: LlmPort
    private lateinit var memory: MemoryPort
    private lateinit var feedbackStore: FeedbackPort
    private lateinit var tmpDir: File

    private val baseConfig = ReflectionServiceConfig(
        enabled       = true,
        dryRun        = false,
        lookbackHours = 24,
        updateSoul    = true,
        updateSkills  = true,
        updateUser    = true
    )

    @BeforeEach
    fun setup() {
        llm = mockk()
        memory = mockk(relaxed = true)
        feedbackStore = mockk(relaxed = true)
        tmpDir = Files.createTempDirectory("reflection-test").toFile()
    }

    @AfterEach
    fun teardown() {
        tmpDir.deleteRecursively()
    }

    private fun makeService(
        config: ReflectionServiceConfig = baseConfig,
        notifyFn: (suspend (String) -> Unit)? = null
    ) = ReflectionService(
        llm           = llm,
        memory        = memory,
        feedbackStore = feedbackStore,
        workspace     = com.assistant.workspace.WorkspaceLoader(tmpDir),
        config        = config,
        notifyFn      = notifyFn
    )

    @Test
    fun `reflect does nothing when no unreflected sessions`() = runTest {
        coEvery { feedbackStore.unreflectedSessions(any(), any()) } returns emptyList()

        makeService().reflect("default")

        coVerify(exactly = 0) { llm.complete(any()) }
        coVerify(exactly = 0) { memory.saveFact(any(), any()) }
    }

    @Test
    fun `reflect calls LLM and saves extracted facts`() = runTest {
        coEvery { feedbackStore.unreflectedSessions("default", any()) } returns listOf("sess-1")
        coEvery { feedbackStore.signalsFor("default", any()) } returns emptyList()
        coEvery { memory.history("sess-1", any()) } returns listOf(
            Message(sender = "user", text = "I prefer dark mode", channel = Channel.TELEGRAM)
        )
        coEvery { llm.complete(any()) } returns """
            NEW_FACTS:
            - User prefers dark mode

            SUMMARY: User mentioned a UI preference
        """.trimIndent()

        makeService().reflect("default")

        coVerify { memory.saveFact("default", "User prefers dark mode") }
        coVerify { feedbackStore.markReflected(listOf("sess-1")) }
    }

    @Test
    fun `reflect creates new skill from SKILL_NEEDED section`() = runTest {
        coEvery { feedbackStore.unreflectedSessions("default", any()) } returns listOf("sess-2")
        coEvery { feedbackStore.signalsFor("default", any()) } returns emptyList()
        coEvery { memory.history("sess-2", any()) } returns emptyList()
        coEvery { llm.complete(any()) } returns """
            SKILL_NEEDED:
            name: deploy_helper
            description: Helps with deployment tasks
            triggers: deploy, release, ship
            body: |
              - Check CI status
              - Run tests

            SUMMARY: User frequently asks about deployments
        """.trimIndent()

        makeService().reflect("default")

        val skillFile = File(tmpDir, "skills/deploy-helper.md")
        assertTrue(skillFile.exists(), "Skill file should be created")
        val content = skillFile.readText()
        assertTrue(content.contains("name: deploy_helper"))
        assertTrue(content.contains("deploy, release, ship"))
    }

    @Test
    fun `reflect appends soul patch`() = runTest {
        coEvery { feedbackStore.unreflectedSessions("default", any()) } returns listOf("sess-3")
        coEvery { feedbackStore.signalsFor("default", any()) } returns emptyList()
        coEvery { memory.history("sess-3", any()) } returns emptyList()
        coEvery { llm.complete(any()) } returns """
            SOUL_PATCH:
            Be more concise in technical explanations.

            SUMMARY: User prefers brevity
        """.trimIndent()

        makeService().reflect("default")

        val soulFile = File(tmpDir, "Soul.md")
        assertTrue(soulFile.exists(), "Soul.md should be created")
        assertTrue(soulFile.readText().contains("Be more concise"))
    }

    @Test
    fun `reflect in dry-run mode calls notifyFn instead of applying changes`() = runTest {
        coEvery { feedbackStore.unreflectedSessions("default", any()) } returns listOf("sess-4")
        coEvery { feedbackStore.signalsFor("default", any()) } returns emptyList()
        coEvery { memory.history("sess-4", any()) } returns emptyList()
        coEvery { llm.complete(any()) } returns """
            NEW_FACTS:
            - User likes Kotlin

            SUMMARY: Language preference noted
        """.trimIndent()

        var notified: String? = null
        val dryRunConfig = baseConfig.copy(dryRun = true)
        makeService(dryRunConfig, notifyFn = { notified = it }).reflect("default")

        // Should NOT save facts
        coVerify(exactly = 0) { memory.saveFact(any(), any()) }
        // Should notify
        assertNotNull(notified, "notifyFn should have been called")
        assertTrue(notified!!.contains("dry-run"), "Report should mention dry-run")
        // Sessions still marked as reflected
        coVerify { feedbackStore.markReflected(listOf("sess-4")) }
    }

    @Test
    fun `reflect updates user profile field`() = runTest {
        coEvery { feedbackStore.unreflectedSessions("default", any()) } returns listOf("sess-5")
        coEvery { feedbackStore.signalsFor("default", any()) } returns emptyList()
        coEvery { memory.history("sess-5", any()) } returns emptyList()
        coEvery { llm.complete(any()) } returns """
            USER_PROFILE:
            timezone: Europe/Berlin

            SUMMARY: Timezone observed
        """.trimIndent()

        makeService().reflect("default")

        val userFile = File(tmpDir, "USER.md")
        assertTrue(userFile.exists())
        assertTrue(userFile.readText().contains("timezone: Europe/Berlin"))
    }

    @Test
    fun `reflect marks sessions as reflected after processing`() = runTest {
        coEvery { feedbackStore.unreflectedSessions("default", any()) } returns listOf("s1", "s2")
        coEvery { feedbackStore.signalsFor("default", any()) } returns emptyList()
        coEvery { memory.history(any(), any()) } returns emptyList()
        coEvery { llm.complete(any()) } returns "SUMMARY: nothing to learn"

        makeService().reflect("default")

        coVerify { feedbackStore.markReflected(listOf("s1", "s2")) }
    }
}
