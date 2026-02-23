package com.assistant.workspace

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class WorkspaceLoaderTest {

    @TempDir
    lateinit var tmpDir: File

    @Test
    fun `missing Soul returns null`() = runTest {
        assertNull(WorkspaceLoader(tmpDir).loadSoul())
    }

    @Test
    fun `valid Soul md is loaded`() = runTest {
        File(tmpDir, "Soul.md").writeText("Be helpful and concise.")
        assertEquals("Be helpful and concise.", WorkspaceLoader(tmpDir).loadSoul())
    }

    @Test
    fun `missing IDENTITY returns null`() = runTest {
        assertNull(WorkspaceLoader(tmpDir).loadIdentity())
    }

    @Test
    fun `IDENTITY frontmatter parsed correctly`() = runTest {
        File(tmpDir, "IDENTITY.md").writeText("---\nname: Aria\nemoji: 🤖\nvibe: concise, direct\n---\n")
        val identity = WorkspaceLoader(tmpDir).loadIdentity()
        assertNotNull(identity)
        assertEquals("Aria", identity!!.name)
        assertEquals("🤖", identity.emoji)
        assertEquals("concise, direct", identity.vibe)
    }

    @Test
    fun `IDENTITY without name returns null`() = runTest {
        File(tmpDir, "IDENTITY.md").writeText("---\nemoji: 🤖\nvibe: concise\n---\n")
        assertNull(WorkspaceLoader(tmpDir).loadIdentity())
    }

    @Test
    fun `missing bootstrap dir returns null`() = runTest {
        assertNull(WorkspaceLoader(tmpDir).loadBootstrap())
    }

    @Test
    fun `bootstrap files concatenated in filename order`() = runTest {
        val bootstrapDir = File(tmpDir, "bootstrap").also { it.mkdirs() }
        File(bootstrapDir, "01-second.md").writeText("Second")
        File(bootstrapDir, "00-first.md").writeText("First")
        val result = WorkspaceLoader(tmpDir).loadBootstrap()
        assertNotNull(result)
        val idxFirst = result!!.indexOf("First")
        val idxSecond = result.indexOf("Second")
        assertTrue(idxFirst < idxSecond, "00-first.md should appear before 01-second.md")
    }

    @Test
    fun `empty bootstrap dir returns null`() = runTest {
        File(tmpDir, "bootstrap").mkdirs()
        assertNull(WorkspaceLoader(tmpDir).loadBootstrap())
    }

    @Test
    fun `missing skills dir returns empty list`() = runTest {
        assertTrue(WorkspaceLoader(tmpDir).loadSkills().isEmpty())
    }

    @Test
    fun `skills filtered by enabled false`() = runTest {
        val skillsDir = File(tmpDir, "skills").also { it.mkdirs() }
        File(skillsDir, "github.md").writeText(
            "---\nname: github\nenabled: true\ndescription: GitHub\n---\nUse gh CLI"
        )
        File(skillsDir, "disabled.md").writeText(
            "---\nname: disabled\nenabled: false\ndescription: Disabled\n---\nDisabled skill"
        )
        val skills = WorkspaceLoader(tmpDir).loadSkills()
        assertEquals(1, skills.size)
        assertEquals("github", skills[0].name)
        assertEquals("Use gh CLI", skills[0].body)
    }

    @Test
    fun `skill without enabled field is included by default`() = runTest {
        val skillsDir = File(tmpDir, "skills").also { it.mkdirs() }
        File(skillsDir, "nofield.md").writeText(
            "---\nname: nofield\ndescription: No enabled field\n---\nSkill body"
        )
        val skills = WorkspaceLoader(tmpDir).loadSkills()
        assertEquals(1, skills.size)
        assertEquals("nofield", skills[0].name)
    }

    @Test
    fun `loadUser returns null when USER_md absent`() = runTest {
        assertNull(WorkspaceLoader(tmpDir).loadUser())
    }

    @Test
    fun `loadUser returns trimmed content when USER_md present`() = runTest {
        File(tmpDir, "USER.md").writeText("name: Timur\ntimezone: Europe/Bratislava\n")
        val result = WorkspaceLoader(tmpDir).loadUser()
        assertNotNull(result)
        assertEquals("name: Timur\ntimezone: Europe/Bratislava", result)
    }

    @Test
    fun `lastChatId returns null when file absent`() {
        assertNull(WorkspaceLoader(tmpDir).lastChatId())
    }

    @Test
    fun `lastChatId reads value from file`() {
        File(tmpDir, "last-chat-id").writeText("987654321")
        assertEquals(987654321L, WorkspaceLoader(tmpDir).lastChatId())
    }
}
