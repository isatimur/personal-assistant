package com.assistant.agent

import com.assistant.agent.grpc.AgentRequest
import com.assistant.agent.grpc.AgentResponse
import com.assistant.agent.grpc.AgentServiceGrpcKt
import io.grpc.Server
import io.grpc.ServerBuilder
import io.grpc.inprocess.InProcessServerBuilder
import java.util.concurrent.ConcurrentHashMap

class AgentGrpcServer(
    private val port: Int,
    private val registry: AgentRegistry,
    private val agentName: String
) {
    private lateinit var server: Server
    private val handlers = ConcurrentHashMap<String, suspend (from: String, message: String, ephemeral: Boolean) -> String>()

    fun registerLocalAgent(name: String, handler: suspend (from: String, message: String, ephemeral: Boolean) -> String) {
        handlers[name] = handler
    }

    /** For real use: starts on the configured port and registers with the registry. */
    fun start() {
        server = ServerBuilder.forPort(port)
            .addService(serviceImpl())
            .build().start()
        registry.register(agentName, "localhost:$port")
        Runtime.getRuntime().addShutdownHook(Thread { server.shutdown() })
    }

    /** Test seam: starts in-process with no real port and no registry.register() call. */
    fun startInProcess(serverName: String): Server {
        server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(serviceImpl())
            .build().start()
        return server
    }

    private fun serviceImpl(): AgentServiceGrpcKt.AgentServiceCoroutineImplBase {
        return object : AgentServiceGrpcKt.AgentServiceCoroutineImplBase() {
            override suspend fun ask(request: AgentRequest): AgentResponse {
                val handler = handlers[agentName]
                    ?: return AgentResponse.newBuilder().setText("Error: no handler for '$agentName'").build()
                val text = handler(request.from, request.message, request.ephemeral)
                return AgentResponse.newBuilder().setText(text).build()
            }
        }
    }
}
