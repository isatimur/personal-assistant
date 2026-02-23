package com.assistant.telegram

import com.assistant.ports.MemoryPort
import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal sealed class OnboardingStep {
    object AskUserName : OnboardingStep()
    data class AskTimezone(val userName: String) : OnboardingStep()
    data class AskGoals(val userName: String, val timezone: String) : OnboardingStep()
    data class AskBotName(val userName: String, val timezone: String, val goals: String) : OnboardingStep()
    data class AskVibe(val userName: String, val timezone: String, val goals: String, val botName: String) : OnboardingStep()
    data class AskSoul(val userName: String, val timezone: String, val goals: String, val botName: String, val vibe: String) : OnboardingStep()
}

internal class OnboardingManager(
    private val memory: MemoryPort,
    private val workspaceDir: File
) {
    private val steps = ConcurrentHashMap<Long, OnboardingStep>()

    fun isActive(chatId: Long): Boolean = steps.containsKey(chatId)

    /** Returns true when Soul.md is missing OR IDENTITY.md has no valid `name:` field. */
    fun needsOnboarding(): Boolean {
        val soulFile = File(workspaceDir, "Soul.md")
        if (!soulFile.exists()) return true
        val identityFile = File(workspaceDir, "IDENTITY.md")
        if (!identityFile.exists()) return true
        return !identityFile.readText().lines().any { it.trimStart().startsWith("name:") && it.substringAfter("name:").isNotBlank() }
    }

    /** Puts chatId into AskUserName state and sends greeting. */
    fun start(bot: Bot, chatId: Long) {
        steps[chatId] = OnboardingStep.AskUserName
        bot.sendMessage(ChatId.fromId(chatId), "Hi! I'm setting myself up. What's your name?")
    }

    /** Cancels wizard for chatId. */
    fun cancel(chatId: Long) {
        steps.remove(chatId)
    }

    /** Advances the wizard. Returns true if chatId was in a wizard step. */
    suspend fun handle(bot: Bot, chatId: Long, text: String): Boolean {
        val step = steps[chatId] ?: return false
        when (step) {
            is OnboardingStep.AskUserName -> {
                steps[chatId] = OnboardingStep.AskTimezone(userName = text.trim())
                bot.sendMessage(ChatId.fromId(chatId), "Nice to meet you, ${text.trim()}! What's your timezone? (e.g. Europe/London, America/New_York)")
            }
            is OnboardingStep.AskTimezone -> {
                steps[chatId] = OnboardingStep.AskGoals(userName = step.userName, timezone = text.trim())
                bot.sendMessage(ChatId.fromId(chatId), "What are your main goals or focus areas? (e.g. ship product, learn Kotlin, stay healthy)")
            }
            is OnboardingStep.AskGoals -> {
                steps[chatId] = OnboardingStep.AskBotName(userName = step.userName, timezone = step.timezone, goals = text.trim())
                bot.sendMessage(ChatId.fromId(chatId), "What should I call your assistant?")
            }
            is OnboardingStep.AskBotName -> {
                steps[chatId] = OnboardingStep.AskVibe(userName = step.userName, timezone = step.timezone, goals = step.goals, botName = text.trim())
                bot.sendMessage(ChatId.fromId(chatId), "How should ${text.trim()} communicate? Describe the vibe (e.g. \"direct, a bit dry\").")
            }
            is OnboardingStep.AskVibe -> {
                steps[chatId] = OnboardingStep.AskSoul(userName = step.userName, timezone = step.timezone, goals = step.goals, botName = step.botName, vibe = text.trim())
                bot.sendMessage(ChatId.fromId(chatId), "Describe ${step.botName}'s soul in a few sentences — who are they at their core?")
            }
            is OnboardingStep.AskSoul -> {
                complete(bot, chatId, step.userName, step.timezone, step.goals, step.botName, step.vibe, text.trim())
            }
        }
        return true
    }

    private suspend fun complete(
        bot: Bot,
        chatId: Long,
        userName: String,
        timezone: String,
        goals: String,
        botName: String,
        vibe: String,
        soul: String
    ) {
        workspaceDir.mkdirs()
        File(workspaceDir, "IDENTITY.md").writeText(
            "---\nname: $botName\nemoji: 🤖\nvibe: $vibe\n---\n"
        )
        File(workspaceDir, "Soul.md").writeText(soul)
        File(workspaceDir, "USER.md").writeText(
            "name: $userName\ntimezone: $timezone\ngoals: $goals\n"
        )
        memory.saveFact(chatId.toString(), "User's name is $userName")
        memory.saveFact(chatId.toString(), "User's timezone is $timezone")
        steps.remove(chatId)
        bot.sendMessage(ChatId.fromId(chatId), "✅ All set! I'm $botName 🤖. Nice to meet you, $userName!")
    }
}
