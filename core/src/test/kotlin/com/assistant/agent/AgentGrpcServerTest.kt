package com.assistant.agent

import com.assistant.agent.grpc.AgentRequest
import com.assistant.agent.grpc.AgentServiceGrpcKt
import io.grpc.inprocess.InProcessChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AgentGrpcServerTest {

    @Test
    fun `server handles ask and returns handler response`() = runTest {
        val registry = StaticAgentRegistry(emptyMap())
        val server = AgentGrpcServer(port = 0, registry = registry, agentName = "test-agent")
        server.registerLocalAgent("test-agent") { from, message, _ -> "reply from $from: $message" }

        val grpcServer = server.startInProcess("test-server-1")
        val channel = InProcessChannelBuilder.forName("test-server-1").directExecutor().build()
        val stub = AgentServiceGrpcKt.AgentServiceCoroutineStub(channel)

        val response = withContext(Dispatchers.IO) {
            stub.ask(AgentRequest.newBuilder().setFrom("caller").setMessage("hello").build())
        }

        assertEquals("reply from caller: hello", response.text)
        grpcServer.shutdown()
    }

    @Test
    fun `unregistered agent name returns error text`() = runTest {
        val registry = StaticAgentRegistry(emptyMap())
        val server = AgentGrpcServer(port = 0, registry = registry, agentName = "test-agent")
        // No handler registered

        val grpcServer = server.startInProcess("test-server-2")
        val channel = InProcessChannelBuilder.forName("test-server-2").directExecutor().build()
        val stub = AgentServiceGrpcKt.AgentServiceCoroutineStub(channel)

        val response = withContext(Dispatchers.IO) {
            stub.ask(AgentRequest.newBuilder().setFrom("caller").setMessage("hello").build())
        }

        assertTrue(response.text.startsWith("Error:"))
        grpcServer.shutdown()
    }

    @Test
    fun `ephemeral flag is passed to handler`() = runTest {
        val registry = StaticAgentRegistry(emptyMap())
        val server = AgentGrpcServer(port = 0, registry = registry, agentName = "test-agent")
        server.registerLocalAgent("test-agent") { _, _, ephemeral -> "ephemeral=$ephemeral" }

        val grpcServer = server.startInProcess("test-server-3")
        val channel = InProcessChannelBuilder.forName("test-server-3").directExecutor().build()
        val stub = AgentServiceGrpcKt.AgentServiceCoroutineStub(channel)

        val response = withContext(Dispatchers.IO) {
            stub.ask(AgentRequest.newBuilder().setFrom("c").setMessage("hi").setEphemeral(true).build())
        }

        assertEquals("ephemeral=true", response.text)
        grpcServer.shutdown()
    }
}
