package com.assistant.agent

import com.assistant.agent.grpc.*
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class GrpcAgentBusTest {

    @Test
    fun `routes request to registered gRPC server and returns response`() = runTest {
        val serverName = InProcessServerBuilder.generateName()

        // Start a minimal in-process gRPC server
        val server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(object : AgentServiceGrpcKt.AgentServiceCoroutineImplBase() {
                override suspend fun ask(request: AgentRequest): AgentResponse =
                    AgentResponse.newBuilder().setText("echo:${request.message}").build()
            })
            .build().start()

        // Build a channel for GrpcAgentBus to use
        val channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()

        // Inject the channel directly via channelOverride test seam
        val registry = StaticAgentRegistry(mapOf("worker" to "inprocess:$serverName"))
        val bus = GrpcAgentBus(registry, channelOverride = mapOf("worker" to channel))

        val result = bus.request(from = "caller", to = "worker", message = "hello")
        assertEquals("echo:hello", result)

        channel.shutdownNow()
        server.shutdownNow()
    }

    @Test
    fun `returns error for unknown agent`() = runTest {
        val bus = GrpcAgentBus(StaticAgentRegistry(emptyMap()))
        val result = bus.request(from = "c", to = "ghost", message = "hi")
        assertTrue(result.startsWith("Error:"))
        assertTrue(result.contains("ghost"))
    }
}
