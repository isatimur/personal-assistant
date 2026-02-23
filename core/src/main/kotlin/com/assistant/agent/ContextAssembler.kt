package com.assistant.agent

import com.assistant.domain.*
import com.assistant.ports.*
import com.assistant.workspace.AgentIdentity
import com.assistant.workspace.SkillEntry
import com.assistant.workspace.WorkspaceLoader
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class ContextAssembler(
    private val memory: MemoryPort,
    private val toolRegistry: ToolRegistry,
    private val windowSize: Int = 20,
    private val searchLimit: Int = 5,
    private val workspace: WorkspaceLoader = WorkspaceLoader()
) {
    suspend fun build(session: Session, currentMessage: Message): List<ChatMessage> {
        data class Ctx(
            val bootstrap: String?,
            val identity: AgentIdentity?,
            val soul: String?,
            val user: String?,
            val skills: List<SkillEntry>,
            val facts: List<String>,
            val history: List<Message>,
            val relevant: List<String>,
            val agentProfiles: List<AgentProfile>
        )

        val ctx = coroutineScope {
            val b  = async { workspace.loadBootstrap() }
            val i  = async { workspace.loadIdentity() }
            val s  = async { workspace.loadSoul() }
            val u  = async { workspace.loadUser() }
            val sk = async { workspace.loadSkills() }
            val f  = async { memory.facts(session.userId) }
            val h  = async { memory.history(session.id, windowSize) }
            val r  = async { memory.search(session.userId, currentMessage.text, searchLimit) }
            val ap = async { workspace.loadAgentProfiles() }
            Ctx(b.await(), i.await(), s.await(), u.await(), sk.await(), f.await(), h.await(), r.await(), ap.await())
        }

        val matchedProfile = AgentRouter.route(currentMessage.text, ctx.agentProfiles)

        val systemPrompt = buildString {
            // 1. Bootstrap context
            if (ctx.bootstrap != null) {
                appendLine(ctx.bootstrap)
                appendLine()
            }
            // 2. Identity line
            if (ctx.identity != null) {
                appendLine("Your name is ${ctx.identity.name} ${ctx.identity.emoji}. Your vibe: ${ctx.identity.vibe}.")
                appendLine()
            }
            // 3. Soul or default
            if (ctx.soul != null) {
                appendLine(ctx.soul)
                appendLine()
            } else {
                appendLine("You are a personal AI assistant running locally. Use tools to take real actions.")
                appendLine()
            }
            // 4. User context
            if (ctx.user != null) {
                appendLine("## About you:")
                appendLine(ctx.user)
                appendLine()
            }
            // 5. Available tools
            appendLine("Available tools:\n${toolRegistry.describe()}")
            // 6. Skills
            if (ctx.skills.isNotEmpty()) {
                appendLine()
                appendLine("## Skills")
                ctx.skills.forEach { skill ->
                    appendLine()
                    appendLine("### ${skill.name}")
                    appendLine(skill.body)
                }
            }
            // 7. ReAct format
            appendLine("\nTo use a tool, respond EXACTLY with:")
            appendLine("THOUGHT: <reasoning>")
            appendLine("ACTION: <command_name>")
            appendLine("ARGS: {\"key\": \"value\"}")
            appendLine("\nTo give a final answer: FINAL: <response>")
            // 8. User facts
            if (ctx.facts.isNotEmpty()) {
                appendLine("\nKnown facts about this user:")
                ctx.facts.forEach { appendLine("- $it") }
            }
            // 9. Relevant context
            if (ctx.relevant.isNotEmpty()) {
                appendLine("\nRelevant past context:")
                ctx.relevant.forEach { appendLine(it) }
            }
            // 10. Active agent profile
            if (matchedProfile != null) {
                appendLine()
                appendLine("## Active Agent: ${matchedProfile.name}")
                appendLine(matchedProfile.systemPromptExtension)
            }
        }

        return buildList {
            add(ChatMessage("system", systemPrompt))
            ctx.history.forEach { msg ->
                add(ChatMessage(if (msg.sender == session.userId) "user" else "assistant", msg.text))
            }
            add(ChatMessage("user", currentMessage.text))
        }
    }
}
