package com.assistant.agent

import com.assistant.agent.grpc.*
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

class GrpcAgentBus(
    private val registry: AgentRegistry,
    /** Test seam: pre-built channels injected by name, bypasses address resolution. */
    internal val channelOverride: Map<String, ManagedChannel> = emptyMap()
) : AgentBus {

    private val stubs = ConcurrentHashMap<String, AgentServiceGrpcKt.AgentServiceCoroutineStub>()

    override fun registerAgent(name: String, handler: suspend (String, String, Boolean) -> String) {
        // No-op: server-side registration is handled by AgentGrpcServer
    }

    override suspend fun request(from: String, to: String, message: String, timeoutMs: Long, ephemeral: Boolean): String {
        val channel = channelOverride[to] ?: buildChannel(to)
            ?: return "Error: agent '$to' not found in registry"
        val stub = stubs.getOrPut(to) { AgentServiceGrpcKt.AgentServiceCoroutineStub(channel) }
        return runCatching {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(timeoutMs) {
                    stub.ask(
                        AgentRequest.newBuilder()
                            .setFrom(from)
                            .setMessage(message)
                            .setEphemeral(ephemeral)
                            .build()
                    ).text
                } ?: "Error: agent '$to' timed out after ${timeoutMs}ms"
            }
        }.getOrElse { e -> "Error: gRPC call to '$to' failed: ${e.message}" }
    }

    private fun buildChannel(name: String): ManagedChannel? {
        val address = registry.resolve(name) ?: return null
        val parts = address.split(":")
        return ManagedChannelBuilder
            .forAddress(parts[0], parts[1].toInt())
            .usePlaintext()
            .build()
    }
}
