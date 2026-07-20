package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.letta.mobile.cli.runtime.CliQueryParam
import com.letta.mobile.cli.runtime.buildRestUrl
import com.letta.mobile.cli.runtime.cliHttpClient
import com.letta.mobile.cli.runtime.encodeUrlComponent
import com.letta.mobile.cli.runtime.executeJsonRestRequest
import com.letta.mobile.cli.runtime.formatJsonResponse
import com.letta.mobile.cli.runtime.parseHeaderParams
import com.letta.mobile.cli.runtime.parseQueryParams
import com.letta.mobile.cli.runtime.resolveRequestBody
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

internal fun buildResourceCommands(): List<CliktCommand> =
    resourceDefinitions.map { definition ->
        val commands = definition.endpoints.map { endpoint ->
            if (endpoint.argumentNames.isEmpty()) {
                NoArgResourceEndpointCommand(definition.name, endpoint)
            } else {
                ArgResourceEndpointCommand(definition.name, endpoint)
            }
        } + definition.extraCommands()
        ResourceRootCommand(definition.name).subcommands(*commands.toTypedArray())
    }

internal fun resourceCommandNames(): Set<String> = resourceDefinitions.mapTo(mutableSetOf()) { it.name }

internal fun resourceCommandRouteKeys(): Set<String> =
    resourceDefinitions.flatMap { definition ->
        definition.endpoints.map { endpoint -> "${endpoint.verb} ${endpoint.pathTemplate}" }
    }.toSet() + setOf(
        "POST /v1/agents/import",
        "POST /v1/folders/{folder_id}/upload",
    )

private class ResourceRootCommand(name: String) : CliktCommand(name = name) {
    override fun run() = Unit
}

private abstract class BaseResourceEndpointCommand(
    private val groupName: String,
    protected val endpoint: ResourceEndpoint,
) : AdminShimCommand(
    name = endpoint.name,
    help = endpoint.help,
) {
    private val query by option("--query", "-q", help = "Query parameter as name=value. Repeatable.").multiple()
    private val headers by option("--header", "-H", help = "Request header as name=value. Repeatable.").multiple()
    private val body by option("--body", help = "Raw JSON request body.")
    private val bodyFile by option("--body-file", help = "Path to a JSON request body file.")
    private val compact by option("--compact", help = "Print compact JSON response.").flag(default = false)
    private val raw by option("--raw", help = "Print response body without JSON formatting.").flag(default = false)
    private val allowError by option("--allow-error", help = "Do not fail the process on non-2xx responses.")
        .flag(default = false)

    protected fun runEndpoint(rawValues: List<String>) = runBlocking {
        val values = endpoint.valuesFrom(rawValues, defaultResourceValues())
        val requestBody = resolveRequestBody(body, bodyFile)
            ?: endpoint.bodyTemplate?.let { buildResourceBodyTemplate(it, values) }
        val path = buildResourcePathTemplate(endpoint.pathTemplate, values)
        val url = buildRestUrl(baseUrl, path, parseQueryParams(query))
        val client = cliHttpClient()
        try {
            val response = client.executeJsonRestRequest(
                verb = endpoint.verb,
                url = url,
                token = token,
                body = requestBody,
                headers = parseHeaderParams(headers),
            )
            val text = response.bodyAsText()
            if (response.status.value !in 200..299) {
                System.err.println("[$groupName:${endpoint.name}] HTTP ${response.status.value} ${response.status.description}")
                if (!allowError) {
                    if (text.isNotBlank()) System.err.println(text)
                    throw IllegalStateException("${endpoint.name} failed: HTTP ${response.status.value}")
                }
            }
            formatJsonResponse(text, compact, raw)?.let(::println)
        } finally {
            client.close()
        }
        Unit
    }

    private fun defaultResourceValues(): Map<String, String> {
        val values = mutableMapOf<String, String>()
        defaultAgentId()?.let { values["agent_id"] = it }
        defaultConversationId()?.let { values["conversation_id"] = it }
        defaultProjectId()?.let { values["project_id"] = it }
        return values
    }
}

private class NoArgResourceEndpointCommand(
    groupName: String,
    endpoint: ResourceEndpoint,
) : BaseResourceEndpointCommand(groupName, endpoint) {
    override fun run() {
        runEndpoint(emptyList())
    }
}

private class ArgResourceEndpointCommand(
    groupName: String,
    endpoint: ResourceEndpoint,
) : BaseResourceEndpointCommand(groupName, endpoint) {
    private val pathValues by argument("values").multiple(required = false)

    override fun run() {
        runEndpoint(pathValues)
    }
}

private class AgentImportCommand : AdminShimCommand(
    name = "import",
    help = "Import an agent export JSON file with the same multipart route used by the app.",
) {
    private val file by option("--file", help = "Agent export JSON file to import.").required()
    private val fileName by option("--file-name", help = "Filename to send in multipart metadata.")
    private val overrideName by option("--override-name")
    private val overrideExistingTools by option("--override-existing-tools", help = "true or false.")
    private val projectId by option("--project-id")
    private val stripMessages by option("--strip-messages", help = "true or false.")
    private val compact by option("--compact").flag(default = false)
    private val raw by option("--raw").flag(default = false)
    private val allowError by option("--allow-error").flag(default = false)

    override fun run() = runBlocking {
        val filePath = Path.of(file)
        val bytes = Files.readAllBytes(filePath)
        val uploadName = fileName ?: filePath.fileName.toString()
        val client = cliHttpClient()
        try {
            val response = client.submitFormWithBinaryData(
                url = buildRestUrl(baseUrl, "/v1/agents/import", emptyList()),
                formData = formData {
                    append("file", bytes, Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"$uploadName\"")
                        append(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    })
                    overrideName?.let { append("override_name", it) }
                    overrideExistingTools?.let { append("override_existing_tools", it.validatedBoolean("--override-existing-tools")) }
                    projectId?.let { append("project_id", it) }
                    stripMessages?.let { append("strip_messages", it.validatedBoolean("--strip-messages")) }
                },
            ) {
                bearerAuth(token)
                header(HttpHeaders.Accept, ContentType.Application.Json)
            }
            val text = response.bodyAsText()
            if (response.status.value !in 200..299) {
                System.err.println("[agents:import] HTTP ${response.status.value} ${response.status.description}")
                if (!allowError) {
                    if (text.isNotBlank()) System.err.println(text)
                    throw IllegalStateException("agent import failed: HTTP ${response.status.value}")
                }
            }
            formatJsonResponse(text, compact, raw)?.let(::println)
        } finally {
            client.close()
        }
        Unit
    }
}

private class FolderUploadCommand : AdminShimCommand(
    name = "upload",
    help = "Upload a file to a folder with the same multipart route used by the app.",
) {
    private val folderId by argument("folder-id")
    private val file by option("--file", help = "File to upload.").required()
    private val fileName by option("--file-name", help = "Filename to send in multipart metadata.")
    private val duplicateHandling by option("--duplicate-handling")
    private val customName by option("--name")
    private val contentType by option("--content-type")
    private val compact by option("--compact").flag(default = false)
    private val raw by option("--raw").flag(default = false)
    private val allowError by option("--allow-error").flag(default = false)

    override fun run() = runBlocking {
        val filePath = Path.of(file)
        val bytes = Files.readAllBytes(filePath)
        val uploadName = fileName ?: filePath.fileName.toString()
        val query = listOfNotNull(
            duplicateHandling?.let { CliQueryParam("duplicate_handling", it) },
            customName?.let { CliQueryParam("name", it) },
        )
        val path = "/v1/folders/${encodeUrlComponent(folderId)}/upload"
        val client = cliHttpClient()
        try {
            val response = client.submitFormWithBinaryData(
                url = buildRestUrl(baseUrl, path, query),
                formData = formData {
                    append("file", bytes, Headers.build {
                        append(HttpHeaders.ContentDisposition, "filename=\"$uploadName\"")
                        append(HttpHeaders.ContentType, contentType ?: ContentType.Application.OctetStream.toString())
                    })
                },
            ) {
                bearerAuth(token)
                header(HttpHeaders.Accept, ContentType.Application.Json)
            }
            val text = response.bodyAsText()
            if (response.status.value !in 200..299) {
                System.err.println("[folders:upload] HTTP ${response.status.value} ${response.status.description}")
                if (!allowError) {
                    if (text.isNotBlank()) System.err.println(text)
                    throw IllegalStateException("folder upload failed: HTTP ${response.status.value}")
                }
            }
            formatJsonResponse(text, compact, raw)?.let(::println)
        } finally {
            client.close()
        }
        Unit
    }
}

private data class ResourceDefinition(
    val name: String,
    val endpoints: List<ResourceEndpoint>,
    val extraCommands: () -> List<CliktCommand> = { emptyList() },
)

private data class ResourceEndpoint(
    val name: String,
    val verb: String,
    val pathTemplate: String,
    val help: String,
    val bodyTemplate: String? = null,
    val argumentNames: List<String> = inferResourceArgumentNames(pathTemplate, bodyTemplate),
) {
    fun valuesFrom(rawValues: List<String>, defaults: Map<String, String> = emptyMap()): Map<String, String> {
        if (rawValues.size > argumentNames.size) {
            throw valueCountError()
        }
        if (rawValues.size == argumentNames.size) {
            return argumentNames.zip(rawValues).toMap()
        }
        val missingCount = argumentNames.size - rawValues.size
        val namesToDefault = argumentNames.filter { defaults[it] != null }.take(missingCount).toSet()
        if (namesToDefault.size != missingCount) {
            throw valueCountError()
        }
        val rawIterator = rawValues.iterator()
        return argumentNames.associateWith { name ->
            if (name in namesToDefault) {
                defaults.getValue(name)
            } else {
                rawIterator.next()
            }
        }
    }

    private fun valueCountError(): UsageError {
        val expected = if (argumentNames.isEmpty()) "no path values" else argumentNames.joinToString(" ")
        return UsageError("$name expects $expected")
    }
}

internal fun inferResourceArgumentNames(vararg templates: String?): List<String> =
    templates.filterNotNull()
        .flatMap { template -> resourcePlaceholder.findAll(template).map { it.groupValues[1] } }
        .distinct()

internal fun buildResourcePathTemplate(template: String, values: Map<String, String>): String =
    buildResourceTemplate(template, values) { encodeUrlComponent(it) }

internal fun buildResourceBodyTemplate(template: String, values: Map<String, String>): String =
    buildResourceTemplate(template, values) { JsonPrimitive(it).toString() }

private fun buildResourceTemplate(
    template: String,
    values: Map<String, String>,
    encode: (String) -> String,
): String = resourcePlaceholder.replace(template) { match ->
    val name = match.groupValues[1]
    val value = values[name] ?: throw UsageError("Missing value for $name")
    encode(value)
}

private fun String.validatedBoolean(optionName: String): String {
    val normalized = lowercase()
    if (normalized !in setOf("true", "false")) {
        throw UsageError("$optionName must be true or false")
    }
    return normalized
}

private val resourcePlaceholder = Regex("\\{([A-Za-z0-9_]+)}")

private fun e(
    name: String,
    verb: String,
    path: String,
    help: String,
    bodyTemplate: String? = null,
    argumentNames: List<String> = inferResourceArgumentNames(path, bodyTemplate),
): ResourceEndpoint = ResourceEndpoint(
    name = name,
    verb = verb,
    pathTemplate = path,
    help = help,
    bodyTemplate = bodyTemplate,
    argumentNames = argumentNames,
)

private val resourceDefinitions = listOf(
    ResourceDefinition(
        name = "agents",
        endpoints = listOf(
            e("list", "GET", "/v1/agents", "List agents."),
            e("get", "GET", "/v1/agents/{agent_id}", "Get one agent."),
            e("context", "GET", "/v1/agents/{agent_id}/context", "Get context-window details for an agent."),
            e("count", "GET", "/v1/agents/count", "Count agents."),
            e("create", "POST", "/v1/agents", "Create an agent from a JSON body."),
            e("update", "PATCH", "/v1/agents/{agent_id}", "Update an agent from a JSON body."),
            e("delete", "DELETE", "/v1/agents/{agent_id}", "Delete an agent."),
            e("export", "GET", "/v1/agents/{agent_id}/export", "Export an agent."),
            e("attach-archive", "PATCH", "/v1/agents/{agent_id}/archives/attach/{archive_id}", "Attach an archive."),
            e("detach-archive", "PATCH", "/v1/agents/{agent_id}/archives/detach/{archive_id}", "Detach an archive."),
            e("attach-tool", "PATCH", "/v1/agents/{agent_id}/tools/attach/{tool_id}", "Attach a tool."),
            e("detach-tool", "PATCH", "/v1/agents/{agent_id}/tools/detach/{tool_id}", "Detach a tool."),
            e("attach-identity", "PATCH", "/v1/agents/{agent_id}/identities/attach/{identity_id}", "Attach identity."),
            e("detach-identity", "PATCH", "/v1/agents/{agent_id}/identities/detach/{identity_id}", "Detach identity."),
            e("blocks", "GET", "/v1/agents/{agent_id}/core-memory/blocks", "List agent core-memory blocks."),
            e("get-block", "GET", "/v1/agents/{agent_id}/core-memory/blocks/{block_label}", "Get an agent block."),
            e("update-block", "PATCH", "/v1/agents/{agent_id}/core-memory/blocks/{block_label}", "Update an agent block."),
            e("attach-block", "PATCH", "/v1/agents/{agent_id}/core-memory/blocks/attach/{block_id}", "Attach a block."),
            e("detach-block", "PATCH", "/v1/agents/{agent_id}/core-memory/blocks/detach/{block_id}", "Detach a block."),
            e("messages", "GET", "/v1/agents/{agent_id}/messages", "List agent messages."),
            e("send-message", "POST", "/v1/agents/{agent_id}/messages", "Send an agent message from a JSON body."),
            e("reset-messages", "PATCH", "/v1/agents/{agent_id}/reset-messages", "Reset agent messages.", bodyTemplate = "{}"),
            e("cancel-messages", "POST", "/v1/agents/{agent_id}/messages/cancel", "Cancel agent message runs."),
        ),
        extraCommands = { listOf(AgentImportCommand()) },
    ),
    ResourceDefinition(
        name = "conversations",
        endpoints = listOf(
            e("list", "GET", "/v1/conversations", "List conversations."),
            e("get", "GET", "/v1/conversations/{conversation_id}", "Get a conversation."),
            e("create", "POST", "/v1/conversations", "Create a conversation."),
            e("update", "PATCH", "/v1/conversations/{conversation_id}", "Update a conversation."),
            e("delete", "DELETE", "/v1/conversations/{conversation_id}", "Delete a conversation."),
            e("fork", "POST", "/v1/conversations/{conversation_id}/fork", "Fork a conversation."),
            e("cancel", "POST", "/v1/conversations/{conversation_id}/cancel", "Cancel conversation activity."),
            e("recompile", "POST", "/v1/conversations/{conversation_id}/recompile", "Recompile a conversation."),
            e("messages", "GET", "/v1/conversations/{conversation_id}/messages", "List conversation messages."),
            e("send-message", "POST", "/v1/conversations/{conversation_id}/messages", "Send a conversation message."),
            e("stream", "POST", "/v1/conversations/{conversation_id}/stream", "Resume the active conversation stream.", bodyTemplate = "{}"),
        ),
    ),
    ResourceDefinition(
        name = "messages",
        endpoints = listOf(
            e("search", "POST", "/v1/messages/search", "Search messages."),
        ),
    ),
    ResourceDefinition(
        name = "message-batches",
        endpoints = listOf(
            e("list", "GET", "/v1/messages/batches", "List message batches."),
            e("create", "POST", "/v1/messages/batches", "Create a message batch."),
            e("get", "GET", "/v1/messages/batches/{batch_id}", "Get a message batch."),
            e("messages", "GET", "/v1/messages/batches/{batch_id}/messages", "List messages in a batch."),
            e("cancel", "PATCH", "/v1/messages/batches/{batch_id}/cancel", "Cancel a message batch."),
        ),
    ),
    ResourceDefinition(
        name = "tools",
        endpoints = listOf(
            e("list", "GET", "/v1/tools", "List tools."),
            e("get", "GET", "/v1/tools/{tool_id}", "Get a tool."),
            e("count", "GET", "/v1/tools/count", "Count tools."),
            e("create", "POST", "/v1/tools", "Create a tool."),
            e("upsert", "PUT", "/v1/tools", "Upsert a tool."),
            e("update", "PATCH", "/v1/tools/{tool_id}", "Update a tool."),
            e("generate-schema", "POST", "/v1/tools/generate-schema", "Generate a tool schema."),
            e("delete", "DELETE", "/v1/tools/{tool_id}", "Delete a tool."),
            e("attach-to-agent", "PATCH", "/v1/agents/{agent_id}/tools/attach/{tool_id}", "Attach tool to agent."),
            e("detach-from-agent", "PATCH", "/v1/agents/{agent_id}/tools/detach/{tool_id}", "Detach tool from agent."),
        ),
    ),
    ResourceDefinition(
        name = "blocks",
        endpoints = listOf(
            e("list", "GET", "/v1/blocks", "List blocks."),
            e("get", "GET", "/v1/blocks/{block_id}", "Get a block."),
            e("count", "GET", "/v1/blocks/count", "Count blocks."),
            e("create", "POST", "/v1/blocks", "Create a block."),
            e("update", "PATCH", "/v1/blocks/{block_id}", "Update a block."),
            e("delete", "DELETE", "/v1/blocks/{block_id}", "Delete a block."),
            e("agents", "GET", "/v1/blocks/{block_id}/agents", "List agents using a block."),
            e("attach-identity", "PATCH", "/v1/blocks/{block_id}/identities/attach/{identity_id}", "Attach identity."),
            e("detach-identity", "PATCH", "/v1/blocks/{block_id}/identities/detach/{identity_id}", "Detach identity."),
        ),
    ),
    ResourceDefinition(
        name = "archives",
        endpoints = listOf(
            e("list", "GET", "/v1/archives/", "List archives."),
            e("get", "GET", "/v1/archives/{archive_id}", "Get an archive."),
            e("create", "POST", "/v1/archives/", "Create an archive."),
            e("update", "PATCH", "/v1/archives/{archive_id}", "Update an archive."),
            e("delete", "DELETE", "/v1/archives/{archive_id}", "Delete an archive."),
            e("agents", "GET", "/v1/archives/{archive_id}/agents", "List agents using an archive."),
            e("delete-passage", "DELETE", "/v1/archives/{archive_id}/passages/{passage_id}", "Delete an archive passage."),
        ),
    ),
    ResourceDefinition(
        name = "passages",
        endpoints = listOf(
            e("list", "GET", "/v1/agents/{agent_id}/archival-memory", "List archival-memory passages."),
            e("create", "POST", "/v1/agents/{agent_id}/archival-memory", "Create an archival-memory passage."),
            e("delete", "DELETE", "/v1/agents/{agent_id}/archival-memory/{passage_id}", "Delete a passage."),
        ),
    ),
    ResourceDefinition(
        name = "folders",
        endpoints = listOf(
            e("list", "GET", "/v1/folders/", "List folders."),
            e("get", "GET", "/v1/folders/{folder_id}", "Get a folder."),
            e("count", "GET", "/v1/folders/count", "Count folders."),
            e("metadata", "GET", "/v1/folders/metadata", "Get folder metadata."),
            e("create", "POST", "/v1/folders/", "Create a folder."),
            e("update", "PATCH", "/v1/folders/{folder_id}", "Update a folder."),
            e("delete", "DELETE", "/v1/folders/{folder_id}", "Delete a folder."),
            e("agents", "GET", "/v1/folders/{folder_id}/agents", "List folder agents."),
            e("passages", "GET", "/v1/folders/{folder_id}/passages", "List folder passages."),
            e("files", "GET", "/v1/folders/{folder_id}/files", "List folder files."),
            e("delete-file", "DELETE", "/v1/folders/{folder_id}/{file_id}", "Delete a folder file."),
        ),
        extraCommands = { listOf(FolderUploadCommand()) },
    ),
    ResourceDefinition(
        name = "groups",
        endpoints = listOf(
            e("list", "GET", "/v1/groups/", "List groups."),
            e("get", "GET", "/v1/groups/{group_id}", "Get a group."),
            e("count", "GET", "/v1/groups/count", "Count groups."),
            e("create", "POST", "/v1/groups/", "Create a group."),
            e("update", "PATCH", "/v1/groups/{group_id}", "Update a group."),
            e("delete", "DELETE", "/v1/groups/{group_id}", "Delete a group."),
            e("send-message", "POST", "/v1/groups/{group_id}/messages", "Send a group message."),
            e("stream-message", "POST", "/v1/groups/{group_id}/messages/stream", "Stream a group message."),
            e("update-message", "PATCH", "/v1/groups/{group_id}/messages/{message_id}", "Update a group message."),
            e("messages", "GET", "/v1/groups/{group_id}/messages", "List group messages."),
            e("reset-messages", "PATCH", "/v1/groups/{group_id}/reset-messages", "Reset group messages."),
        ),
    ),
    ResourceDefinition(
        name = "identities",
        endpoints = listOf(
            e("list", "GET", "/v1/identities/", "List identities."),
            e("get", "GET", "/v1/identities/{identity_id}", "Get an identity."),
            e("count", "GET", "/v1/identities/count", "Count identities."),
            e("create", "POST", "/v1/identities/", "Create an identity."),
            e("upsert", "PUT", "/v1/identities/", "Upsert an identity."),
            e("update", "PATCH", "/v1/identities/{identity_id}", "Update an identity."),
            e("delete", "DELETE", "/v1/identities/{identity_id}", "Delete an identity."),
            e("set-properties", "PUT", "/v1/identities/{identity_id}/properties", "Replace identity properties."),
            e("agents", "GET", "/v1/identities/{identity_id}/agents", "List identity agents."),
            e("blocks", "GET", "/v1/identities/{identity_id}/blocks", "List identity blocks."),
            e("attach-agent", "PATCH", "/v1/agents/{agent_id}/identities/attach/{identity_id}", "Attach identity to agent."),
            e("detach-agent", "PATCH", "/v1/agents/{agent_id}/identities/detach/{identity_id}", "Detach identity from agent."),
        ),
    ),
    ResourceDefinition(
        name = "schedules",
        endpoints = listOf(
            e("list", "GET", "/v1/agents/{agent_id}/schedule", "List agent schedules."),
            e("get", "GET", "/v1/agents/{agent_id}/schedule/{scheduled_message_id}", "Get a scheduled message."),
            e("create", "POST", "/v1/agents/{agent_id}/schedule", "Create a scheduled message."),
            e("delete", "DELETE", "/v1/agents/{agent_id}/schedule/{scheduled_message_id}", "Delete a schedule."),
        ),
    ),
    ResourceDefinition(
        name = "mcp",
        endpoints = listOf(
            e("list", "GET", "/v1/mcp-servers", "List MCP servers."),
            e("get", "GET", "/v1/mcp-servers/{server_id}", "Get an MCP server."),
            e("create", "POST", "/v1/mcp-servers", "Create an MCP server."),
            e("update", "PATCH", "/v1/mcp-servers/{server_id}", "Update an MCP server."),
            e("delete", "DELETE", "/v1/mcp-servers/{server_id}", "Delete an MCP server."),
            e("tools", "GET", "/v1/mcp-servers/{server_id}/tools", "List MCP server tools."),
            e("refresh", "PATCH", "/v1/mcp-servers/{server_id}/refresh", "Refresh an MCP server."),
            e("run-tool", "POST", "/v1/mcp-servers/{server_id}/tools/{tool_id}/run", "Run an MCP server tool."),
        ),
    ),
    ResourceDefinition(
        name = "runs",
        endpoints = listOf(
            e("list", "GET", "/v1/runs/", "List runs."),
            e("get", "GET", "/v1/runs/{run_id}", "Get a run."),
            e("messages", "GET", "/v1/runs/{run_id}/messages", "List run messages."),
            e("usage", "GET", "/v1/runs/{run_id}/usage", "Get run usage."),
            e("metrics", "GET", "/v1/runs/{run_id}/metrics", "Get run metrics."),
            e("steps", "GET", "/v1/runs/{run_id}/steps", "List run steps."),
            e("cancel-agent-messages", "POST", "/v1/agents/{agent_id}/messages/cancel", "Cancel agent message runs."),
            e("delete", "DELETE", "/v1/runs/{run_id}", "Delete a run."),
        ),
    ),
    ResourceDefinition(
        name = "jobs",
        endpoints = listOf(
            e("list", "GET", "/v1/jobs/", "List jobs."),
            e("get", "GET", "/v1/jobs/{job_id}", "Get a job."),
            e("cancel", "PATCH", "/v1/jobs/{job_id}/cancel", "Cancel a job."),
            e("delete", "DELETE", "/v1/jobs/{job_id}", "Delete a job."),
        ),
    ),
    ResourceDefinition(
        name = "steps",
        endpoints = listOf(
            e("list", "GET", "/v1/steps/", "List steps."),
            e("get", "GET", "/v1/steps/{step_id}", "Get a step."),
            e("metrics", "GET", "/v1/steps/{step_id}/metrics", "Get step metrics."),
            e("trace", "GET", "/v1/steps/{step_id}/trace", "Get step trace."),
            e("messages", "GET", "/v1/steps/{step_id}/messages", "List step messages."),
            e("feedback", "PATCH", "/v1/steps/{step_id}/feedback", "Submit step feedback."),
        ),
    ),
    ResourceDefinition(
        name = "models",
        endpoints = listOf(
            e("list", "GET", "/v1/models", "List LLM models."),
            e("embedding", "GET", "/v1/models/embedding", "List embedding models."),
        ),
    ),
    ResourceDefinition(
        name = "providers",
        endpoints = listOf(
            e("list", "GET", "/v1/providers/", "List providers."),
            e("get", "GET", "/v1/providers/{provider_id}", "Get a provider."),
            e("create", "POST", "/v1/providers/", "Create a provider."),
            e("update", "PATCH", "/v1/providers/{provider_id}", "Update a provider."),
            e("check", "POST", "/v1/providers/check", "Check provider credentials from JSON body."),
            e("check-one", "POST", "/v1/providers/{provider_id}/check", "Check a configured provider."),
            e("delete", "DELETE", "/v1/providers/{provider_id}", "Delete a provider."),
        ),
    ),
    ResourceDefinition(
        name = "projects",
        endpoints = listOf(
            e("probe", "GET", "/api/projects", "Probe/list projects; use --query limit=1 for availability check."),
            e("list", "GET", "/api/projects", "List projects."),
            e("get", "GET", "/api/projects/{project_id}", "Get a project."),
            e("create", "POST", "/api/registry/projects", "Create a project."),
            e("update", "PATCH", "/api/registry/projects/{project_id}", "Update a project."),
            e("archive", "PATCH", "/api/registry/projects/{project_id}", "Archive a project.", bodyTemplate = """{"status":"archived"}"""),
            e("delete", "DELETE", "/api/registry/projects/{project_id}", "Delete a project."),
            e("beads-remote", "GET", "/api/projects/{project_id}/beads-remote", "Get beads remote status."),
            e(
                "provision-beads-remote",
                "POST",
                "/api/projects/{project_id}/beads-remote/provision",
                "Provision beads remote.",
                bodyTemplate = """{"push":true}""",
            ),
            e(
                "sync-trigger",
                "POST",
                "/api/sync/trigger",
                "Trigger project sync.",
                bodyTemplate = """{"projectId":{project_id}}""",
            ),
        ),
    ),
    ResourceDefinition(
        name = "project-agents",
        endpoints = listOf(
            e("lookup", "GET", "/api/agents/lookup", "Look up project-manager agent metadata; pass --query repo=<url>."),
        ),
    ),
    ResourceDefinition(
        name = "project-work",
        endpoints = listOf(
            e("ready", "GET", "/api/projects/{project_id}/ready-work", "List ready work for a project."),
            e("issues", "GET", "/api/projects/{project_id}/issues", "List project issues."),
            e("analytics", "GET", "/api/projects/{project_id}/issue-analytics", "Get issue analytics."),
            e("issue", "GET", "/api/issues/{issue_id}", "Get an issue."),
            e("claim", "POST", "/api/issues/{issue_id}/claim", "Claim an issue; pass mutation headers and JSON body."),
            e("unclaim", "POST", "/api/issues/{issue_id}/unclaim", "Unclaim an issue; pass mutation headers.", bodyTemplate = "{}"),
            e("status", "PATCH", "/api/issues/{issue_id}/status", "Update issue status; pass mutation headers and JSON body."),
            e("note", "POST", "/api/issues/{issue_id}/notes", "Add an issue note; pass mutation headers and JSON body."),
            e("close", "POST", "/api/issues/{issue_id}/close", "Close an issue; pass mutation headers and JSON body."),
            e("reopen", "POST", "/api/issues/{issue_id}/reopen", "Reopen an issue; pass mutation headers and JSON body."),
        ),
    ),
    ResourceDefinition(
        name = "debug",
        endpoints = listOf(
            e("health", "GET", "/health", "Get Vibesync health."),
            e("stats", "GET", "/api/stats", "Get Vibesync stats."),
        ),
    ),
    ResourceDefinition(
        name = "vibesync-admin",
        endpoints = listOf(
            e("refresh-agents-md", "POST", "/api/admin/agents-md/refresh", "Refresh AGENTS.md inventory.", bodyTemplate = """{"dryRun":true}"""),
        ),
    ),
)
