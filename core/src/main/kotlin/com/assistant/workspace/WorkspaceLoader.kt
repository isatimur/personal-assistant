package com.assistant.workspace

import com.assistant.agent.AgentProfile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AgentIdentity(val name: String, val emoji: String, val vibe: String)
data class SkillEntry(val name: String, val description: String, val body: String)

class WorkspaceLoader(val workspaceDir: File = File(System.getProperty("user.home"), ".assistant")) {

    private data class Frontmatter(val meta: Map<String, String>, val body: String)

    private fun parseFrontmatter(content: String): Frontmatter {
        val match = Regex("^---\\r?\\n(.*?)\\r?\\n---\\r?\\n?(.*)", RegexOption.DOT_MATCHES_ALL)
            .find(content) ?: return Frontmatter(emptyMap(), content)
        val meta = match.groupValues[1].lines()
            .mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon < 0) return@mapNotNull null
                val key = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim().removeSurrounding("\"")
                key to value
            }
            .toMap()
        return Frontmatter(meta, match.groupValues[2].trim())
    }

    suspend fun loadSoul(): String? = withContext(Dispatchers.IO) {
        val file = File(workspaceDir, "Soul.md")
        if (file.exists()) file.readText().trim() else null
    }

    suspend fun loadIdentity(): AgentIdentity? = withContext(Dispatchers.IO) {
        val file = File(workspaceDir, "IDENTITY.md")
        if (!file.exists()) return@withContext null
        val fm = parseFrontmatter(file.readText())
        val name = fm.meta["name"] ?: return@withContext null
        AgentIdentity(
            name = name,
            emoji = fm.meta["emoji"] ?: "",
            vibe = fm.meta["vibe"] ?: ""
        )
    }

    suspend fun loadBootstrap(): String? = withContext(Dispatchers.IO) {
        val dir = File(workspaceDir, "bootstrap")
        if (!dir.exists() || !dir.isDirectory) return@withContext null
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.sortedBy { it.name }
            ?: return@withContext null
        if (files.isEmpty()) return@withContext null
        files.joinToString("\n\n") { it.readText().trim() }
    }

    suspend fun loadSkills(): List<SkillEntry> = withContext(Dispatchers.IO) {
        val dir = File(workspaceDir, "skills")
        if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.sortedBy { it.name }
            ?: return@withContext emptyList()
        files.mapNotNull { file ->
            val fm = parseFrontmatter(file.readText())
            val enabled = fm.meta["enabled"]?.lowercase() != "false"
            if (!enabled) return@mapNotNull null
            SkillEntry(
                name = fm.meta["name"] ?: file.nameWithoutExtension,
                description = fm.meta["description"] ?: "",
                body = fm.body
            )
        }
    }

    suspend fun loadUser(): String? = withContext(Dispatchers.IO) {
        val file = File(workspaceDir, "USER.md")
        if (file.exists()) file.readText().trim() else null
    }

    suspend fun loadAgentProfiles(): List<AgentProfile> = withContext(Dispatchers.IO) {
        val dir = File(workspaceDir, "agents")
        if (!dir.exists() || !dir.isDirectory) return@withContext emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.sortedBy { it.name }
            ?: return@withContext emptyList()
        files.mapNotNull { file ->
            val fm = parseFrontmatter(file.readText())
            val enabled = fm.meta["enabled"]?.lowercase() != "false"
            if (!enabled) return@mapNotNull null
            val name = fm.meta["name"] ?: file.nameWithoutExtension
            val description = fm.meta["description"] ?: ""
            val triggers = fm.meta["triggers"]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
            AgentProfile(name = name, description = description, triggers = triggers, systemPromptExtension = fm.body)
        }
    }

    suspend fun setUserField(key: String, value: String): Unit = withContext(Dispatchers.IO) {
        val file = File(workspaceDir, "USER.md")
        val existing = if (file.exists()) file.readText() else ""
        val lines = existing.lines().toMutableList()
        val idx = lines.indexOfFirst { it.trimStart().startsWith("$key:") }
        if (idx >= 0) {
            lines[idx] = "$key: $value"
        } else {
            lines.add("$key: $value")
        }
        // Trim trailing blank lines then add final newline
        val result = lines.dropLastWhile { it.isBlank() }.joinToString("\n") + "\n"
        file.parentFile?.mkdirs()
        file.writeText(result)
    }

    fun lastChatId(): Long? {
        val file = File(workspaceDir, "last-chat-id")
        return if (file.exists()) file.readText().trim().toLongOrNull() else null
    }
}
