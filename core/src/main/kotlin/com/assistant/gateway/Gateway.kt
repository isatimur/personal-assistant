package com.assistant.gateway

import com.assistant.agent.AgentEngine
import com.assistant.domain.*
import java.util.concurrent.ConcurrentHashMap

class Gateway(private val engine: AgentEngine) {
    private val sessions = ConcurrentHashMap<String, Session>()

    suspend fun handle(message: Message): String {
        val key = "${message.channel}:${message.sender}"
        val session = sessions.getOrPut(key) { Session(id = key, userId = message.sender, channel = message.channel) }
        return engine.process(session, message)
    }
}
