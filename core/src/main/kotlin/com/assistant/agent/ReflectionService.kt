package com.assistant.agent

import com.assistant.ports.ChatMessage
import com.assistant.ports.FeedbackPort
import com.assistant.ports.LlmPort
import com.assistant.ports.MemoryPort
import com.assistant.ports.Signal
import com.assistant.ports.SignalType
import com.assistant.workspace.WorkspaceLoader

data class NewSkillDraft(
    val name: String,
    val description: String,
    val triggers: List<String>,
    val body: String
)

data class ReflectionResult(
    val newFacts: List<String>,
    val userProfileUpdates: Map<String, String>,
    val newSkill: NewSkillDraft?,
    val soulPatch: String?,
    val summary: String
)

data class ReflectionServiceConfig(
    val enabled: Boolean = false,
    val cron: String = "0 23 * * *",
    val lookbackHours: Int = 24,
    val updateSoul: Boolean = true,
    val updateSkills: Boolean = true,
    val updateUser: Boolean = true,
    val dryRun: Boolean = true
)

private val REFLECTION_SYSTEM_PROMPT =
    "You are a meta-learning system analyzing an AI assistant's recent interactions.\n" +
    "Your job is to extract actionable improvements to the assistant's knowledge and behavior.\n" +
    "Be conservative: only suggest changes that are clearly supported by the evidence.\n" +
    "Output ONLY in the structured format specified. Do not add commentary outside the format."

class ReflectionService(
    private val llm: LlmPort,
    private val memory: MemoryPort,
    private val feedbackStore: FeedbackPort,
    private val workspace: WorkspaceLoader,
    private val config: ReflectionServiceConfig,
    private val notifyFn: (suspend (String) -> Unit)? = null
) {

    suspend fun reflect(userId: String) {
        val sinceMs = System.currentTimeMillis() - config.lookbackHours * 3_600_000L
        val sessions = feedbackStore.unreflectedSessions(userId, sinceMs)
        if (sessions.isEmpty()) return

        val snippets = buildConversationSnippets(sessions)
        val signals = feedbackStore.signalsFor(userId, sinceMs)
        val currentSoul = workspace.loadSoul() ?: ""
        val currentUser = workspace.loadUser() ?: ""
        val currentSkills = workspace.loadSkills().map { it.name }

        val prompt = buildReflectionPrompt(snippets, signals, currentSoul, currentUser, currentSkills, config.lookbackHours)
        val response = llm.complete(listOf(
            ChatMessage("system", REFLECTION_SYSTEM_PROMPT),
            ChatMessage("user", prompt)
        ))

        val parsed = parseReflectionResponse(response)

        if (config.dryRun) {
            notifyFn?.invoke(formatDryRunReport(parsed))
        } else {
            applyLearnings(userId, parsed)
        }

        feedbackStore.markReflected(sessions)
    }

    private suspend fun buildConversationSnippets(sessionIds: List<String>): String {
        val sb = StringBuilder()
        sessionIds.take(5).forEach { sessionId ->
            val messages = memory.history(sessionId, 10)
            messages.forEach { msg ->
                val role = if (msg.sender == "assistant") "ASSISTANT" else "USER"
                sb.appendLine("[SESSION:${sessionId.take(8)}] $role: ${msg.text.take(200)}")
            }
        }
        return sb.toString().trim()
    }

    private suspend fun applyLearnings(userId: String, parsed: ReflectionResult) {
        parsed.newFacts.forEach { memory.saveFact(userId, it) }
        if (config.updateUser) {
            parsed.userProfileUpdates.forEach { (key, value) ->
                workspace.setUserField(key, value)
            }
        }
        if (config.updateSkills && parsed.newSkill != null) {
            workspace.saveSkill(
                name        = parsed.newSkill.name,
                description = parsed.newSkill.description,
                triggers    = parsed.newSkill.triggers,
                body        = parsed.newSkill.body
            )
        }
        if (config.updateSoul && parsed.soulPatch != null) {
            workspace.appendToSoul(parsed.soulPatch)
        }
    }
}

internal fun buildReflectionPrompt(
    conversationSnippets: String,
    signals: List<Signal>,
    currentSoul: String,
    currentUser: String,
    currentSkills: List<String>,
    lookbackHours: Int
): String = buildString {
    appendLine("## Recent conversations (last ${lookbackHours}h)")
    appendLine()
    if (conversationSnippets.isBlank()) {
        appendLine("(no conversation history available)")
    } else {
        appendLine(conversationSnippets)
    }
    appendLine()
    appendLine("## Interaction signals")
    if (signals.isEmpty()) {
        appendLine("(no signals recorded)")
    } else {
        signals.forEach { s ->
            when (s.type) {
                SignalType.CORRECTION  -> appendLine("- CORRECTION in session ${s.sessionId.take(8)}: '${s.context}'")
                SignalType.HIGH_STEPS  -> appendLine("- HIGH_STEPS in session ${s.sessionId.take(8)}: ${s.context}")
                SignalType.TOOL_ERROR  -> appendLine("- TOOL_ERROR in session ${s.sessionId.take(8)}: '${s.context}'")
                SignalType.APPROVAL    -> appendLine("- APPROVAL in session ${s.sessionId.take(8)}: ${s.context}")
            }
        }
    }
    appendLine()
    appendLine("## Current user profile")
    appendLine(currentUser.ifBlank { "(empty)" })
    appendLine()
    appendLine("## Current soul/style (excerpt, first 500 chars)")
    appendLine(currentSoul.take(500).ifBlank { "(empty)" })
    appendLine()
    appendLine("## Already known skills")
    appendLine(currentSkills.ifEmpty { listOf("(none)") }.joinToString(", "))
    appendLine()
    appendLine("---")
    appendLine()
    appendLine("Analyze the above and respond with EXACTLY this structure (omit sections with no content):")
    appendLine()
    appendLine("NEW_FACTS:")
    appendLine("- {fact about the user, only if clearly stated or strongly implied}")
    appendLine()
    appendLine("USER_PROFILE:")
    appendLine("{key}: {value}")
    appendLine()
    appendLine("SKILL_NEEDED:")
    appendLine("name: {snake_case_name}")
    appendLine("description: {one sentence}")
    appendLine("triggers: {comma-separated trigger phrases}")
    appendLine("body: |")
    appendLine("  {2-5 bullet points describing what to do when this skill activates}")
    appendLine()
    appendLine("SOUL_PATCH:")
    appendLine("{one or two sentences to append to Soul.md, only if a strong pattern warrants it}")
    appendLine()
    appendLine("SUMMARY: {one sentence — what was the most important learning from this period}")
}

internal fun parseReflectionResponse(response: String): ReflectionResult {
    val lines = response.lines()
    val newFacts = mutableListOf<String>()
    val userProfile = mutableMapOf<String, String>()
    var skillName = ""
    var skillDesc = ""
    var skillTriggers = listOf<String>()
    val skillBodyLines = mutableListOf<String>()
    var inSkillBody = false
    var soulPatch: String? = null
    var summary = ""

    var section = ""

    for (line in lines) {
        when {
            line.startsWith("NEW_FACTS:") -> { section = "facts"; continue }
            line.startsWith("USER_PROFILE:") -> { section = "user"; continue }
            line.startsWith("SKILL_NEEDED:") -> { section = "skill"; inSkillBody = false; continue }
            line.startsWith("SOUL_PATCH:") -> { section = "soul"; continue }
            line.startsWith("SUMMARY:") -> {
                summary = line.removePrefix("SUMMARY:").trim()
                section = ""
                continue
            }
        }

        when (section) {
            "facts" -> {
                val fact = line.trimStart('-', ' ').trim()
                if (fact.isNotBlank() && !fact.startsWith("{")) newFacts.add(fact)
            }
            "user" -> {
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim()
                    val value = line.substring(colon + 1).trim()
                    if (key.isNotBlank() && value.isNotBlank() && !value.startsWith("{"))
                        userProfile[key] = value
                }
            }
            "skill" -> {
                when {
                    line.startsWith("name:") -> skillName = line.removePrefix("name:").trim()
                    line.startsWith("description:") -> skillDesc = line.removePrefix("description:").trim()
                    line.startsWith("triggers:") -> {
                        skillTriggers = line.removePrefix("triggers:").trim()
                            .split(",").map { it.trim() }.filter { it.isNotBlank() }
                    }
                    line.startsWith("body:") -> { inSkillBody = true }
                    inSkillBody && (line.startsWith("  ") || line.startsWith("\t")) -> {
                        skillBodyLines.add(line.trimStart())
                    }
                    inSkillBody && line.isBlank() -> { /* skip blank lines in body */ }
                    inSkillBody -> { inSkillBody = false }
                }
            }
            "soul" -> {
                if (line.isNotBlank() && !line.startsWith("{")) {
                    soulPatch = ((soulPatch ?: "") + " " + line).trim()
                }
            }
        }
    }

    val newSkill = if (skillName.isNotBlank()) {
        NewSkillDraft(
            name        = skillName,
            description = skillDesc,
            triggers    = skillTriggers,
            body        = skillBodyLines.joinToString("\n")
        )
    } else null

    return ReflectionResult(
        newFacts            = newFacts,
        userProfileUpdates  = userProfile,
        newSkill            = newSkill,
        soulPatch           = soulPatch?.ifBlank { null },
        summary             = summary
    )
}

internal fun formatDryRunReport(result: ReflectionResult): String = buildString {
    appendLine("🧠 Reflection dry-run report")
    appendLine()
    if (result.newFacts.isNotEmpty()) {
        appendLine("NEW FACTS (${result.newFacts.size}):")
        result.newFacts.forEach { appendLine("  - $it") }
        appendLine()
    }
    if (result.userProfileUpdates.isNotEmpty()) {
        appendLine("USER PROFILE UPDATES:")
        result.userProfileUpdates.forEach { (k, v) -> appendLine("  $k: $v") }
        appendLine()
    }
    if (result.newSkill != null) {
        appendLine("NEW SKILL: ${result.newSkill.name}")
        appendLine("  ${result.newSkill.description}")
        appendLine()
    }
    if (result.soulPatch != null) {
        appendLine("SOUL PATCH:")
        appendLine("  ${result.soulPatch}")
        appendLine()
    }
    appendLine("SUMMARY: ${result.summary}")
    appendLine()
    appendLine("(dry-run mode — no changes applied)")
}.trim()
