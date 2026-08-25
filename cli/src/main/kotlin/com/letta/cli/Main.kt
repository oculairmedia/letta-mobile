package com.letta.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LettaMessage(
    val id: String,
    val message_type: String,
    val date: String? = null,
    val content: String? = null,
    val reasoning: String? = null,
    val tool_calls: List<ToolCall>? = null,
)

@Serializable
data class ToolCall(
    val id: String? = null,
    val name: String? = null,
    val arguments: String? = null,
)

class LettaCli : CliktCommand(name = "letta-cli") {
    override fun run() = Unit
}

class Messages : CliktCommand() {
    override fun help(context: Context) = "List messages for an agent"

    private val baseUrl by option("--url", "-u", envvar = "LETTA_URL", help = "Letta server URL")
        .default("http://192.168.50.90:8289")
    private val agentId by argument(help = "Agent ID")
    private val conversationId by option("--conversation", "-c", help = "Conversation ID")
    private val limit by option("--limit", "-l", help = "Number of messages").int().default(10)
    private val order by option("--order", "-o", help = "Order: asc or desc").default("desc")
    private val after by option("--after", "-a", help = "Cursor: messages after this ID")
    private val before by option("--before", "-b", help = "Cursor: messages before this ID")

    override fun run() = runBlocking {
        val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }

        try {
            val response: List<LettaMessage> = client.get("$baseUrl/v1/agents/$agentId/messages") {
                parameter("limit", limit)
                parameter("order", order)
                conversationId?.let { parameter("conversation_id", it) }
                after?.let { parameter("after", it) }
                before?.let { parameter("before", it) }
            }.body()

            println("=== ${response.size} messages (order=$order, limit=$limit) ===\n")
            
            response.forEach { msg ->
                val preview = when {
                    msg.content != null -> msg.content.take(80).replace("\n", " ")
                    msg.reasoning != null -> "[reasoning] ${msg.reasoning.take(60)}"
                    msg.tool_calls?.isNotEmpty() == true -> "[tool] ${msg.tool_calls.first().name}"
                    else -> "[${msg.message_type}]"
                }
                println("${msg.date?.take(19) ?: "?"} | ${msg.message_type.padEnd(20)} | $preview")
                println("  ID: ${msg.id}")
            }
            
            println("\n--- Last message ID: ${response.lastOrNull()?.id ?: "none"} ---")
            println("--- First message ID: ${response.firstOrNull()?.id ?: "none"} ---")
        } finally {
            client.close()
        }
    }
}

class Count : CliktCommand() {
    override fun help(context: Context) = "Count agents, tools, blocks, or conversations"

    private val baseUrl by option("--url", "-u", envvar = "LETTA_URL", help = "Letta server URL")
        .default("http://192.168.50.90:8289")
    private val resource by argument(help = "Resource: agents, tools, blocks")

    override fun run() = runBlocking {
        val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        try {
            val count: Int = when (resource) {
                "agents" -> client.get("$baseUrl/v1/agents/count").body()
                "tools" -> client.get("$baseUrl/v1/tools/count").body()
                "blocks" -> client.get("$baseUrl/v1/blocks/count").body()
                else -> {
                    println("Unknown resource: $resource (try: agents, tools, blocks)")
                    return@runBlocking
                }
            }
            println("$resource count: $count")
        } finally {
            client.close()
        }
    }
}

class CheckNew : CliktCommand() {
    override fun help(context: Context) = "Check for new messages after a cursor"

    private val baseUrl by option("--url", "-u", envvar = "LETTA_URL", help = "Letta server URL")
        .default("http://192.168.50.90:8289")
    private val agentId by argument(help = "Agent ID")
    private val afterId by argument(help = "Message ID to check after")
    private val conversationId by option("--conversation", "-c", help = "Conversation ID")

    override fun run() = runBlocking {
        val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }

        try {
            val response: List<LettaMessage> = client.get("$baseUrl/v1/agents/$agentId/messages") {
                parameter("limit", 50)
                parameter("order", "asc")
                parameter("after", afterId)
                conversationId?.let { parameter("conversation_id", it) }
            }.body()

            println("=== ${response.size} NEW messages after $afterId ===\n")
            
            response.forEach { msg ->
                val preview = when {
                    msg.content != null -> msg.content.take(80).replace("\n", " ")
                    msg.reasoning != null -> "[reasoning] ${msg.reasoning.take(60)}"
                    msg.tool_calls?.isNotEmpty() == true -> "[tool] ${msg.tool_calls.first().name}"
                    else -> "[${msg.message_type}]"
                }
                println("${msg.date?.take(19) ?: "?"} | ${msg.message_type.padEnd(20)} | $preview")
            }
            
            if (response.isEmpty()) {
                println("No new messages found.")
            }
        } finally {
            client.close()
        }
    }
}

fun main(args: Array<String>) = LettaCli()
    .subcommands(Messages(), Count(), CheckNew())
    .main(args)
