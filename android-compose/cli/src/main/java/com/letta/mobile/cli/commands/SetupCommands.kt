package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.letta.mobile.cli.runtime.CliProfileStore
import com.letta.mobile.cli.runtime.CliSetupDocument
import com.letta.mobile.cli.runtime.CliSetupLink
import com.letta.mobile.cli.runtime.CliSetupLinks
import com.letta.mobile.cli.runtime.CliSetupPlan
import com.letta.mobile.cli.runtime.CliSetupPlanStep
import com.letta.mobile.cli.runtime.CliSetupResource
import com.letta.mobile.cli.runtime.CliSetupResources
import com.letta.mobile.cli.runtime.CliSetupSchedule
import com.letta.mobile.cli.runtime.asObjectList
import com.letta.mobile.cli.runtime.buildRestUrl
import com.letta.mobile.cli.runtime.cliHttpClient
import com.letta.mobile.cli.runtime.executeJsonRestRequest
import com.letta.mobile.cli.runtime.readCliSetupDocument
import com.letta.mobile.cli.runtime.stringField
import com.letta.mobile.cli.runtime.writeCliSetupDocument
import com.letta.mobile.cli.runtime.writeCliSetupPlan
import io.ktor.client.HttpClient
import io.ktor.client.statement.bodyAsText
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal class SetupCommand : CliktCommand(name = "setup") {
    override fun run() = Unit
}

internal class SetupApplyCommand : AdminShimCommand(
    name = "apply",
    help = "Apply a declarative CLI setup JSON/YAML file.",
) {
    private val file by option("--file", "-f", help = "Setup JSON/YAML file.").required()
    private val dryRun by option("--dry-run", help = "Print the plan without mutating profiles or server state.")
        .flag(default = false)
    private val compact by option("--compact", help = "Print compact JSON plan output.").flag(default = false)

    override fun run() = runBlocking {
        val document = readCliSetupDocument(Paths.get(file))
        val applier = CliSetupApplier(baseUrl, if (document.hasServerWork()) token else optionalToken.orEmpty())
        val plan = applier.apply(document, dryRun)
        println(writeCliSetupPlan(plan, compact))
    }
}

internal class SetupExportCommand : AdminShimCommand(
    name = "export",
    help = "Export CLI profiles and server state as a declarative setup document.",
) {
    private val out by option("--out", help = "Write setup JSON to this file instead of stdout.")
    private val profilesOnly by option("--profiles-only", help = "Export only local CLI profile state.")
        .flag(default = false)
    private val redactToken by option("--redact-token", help = "Replace profile tokens with <redacted>.")
        .flag(default = false)
    private val skipErrors by option("--skip-errors", help = "Skip server resources that return non-2xx responses.")
        .flag(default = false)
    private val compact by option("--compact", help = "Print compact JSON output.").flag(default = false)

    override fun run() = runBlocking {
        val exporter = CliSetupExporter(baseUrl, if (profilesOnly) optionalToken.orEmpty() else token)
        val document = exporter.export(profilesOnly, redactToken, skipErrors)
        val output = writeCliSetupDocument(document, compact)
        if (out == null) {
            println(output)
        } else {
            val path = Paths.get(out)
            path.parent?.let { Files.createDirectories(it) }
            Files.write(path, output.toByteArray(Charsets.UTF_8))
            println(jsonStatus("exported", path.toString()))
        }
    }
}

private fun CliSetupDocument.hasServerWork(): Boolean =
    resources != CliSetupResources() || links != CliSetupLinks()

private class CliSetupApplier(
    private val baseUrl: String,
    private val token: String,
) {
    suspend fun apply(document: CliSetupDocument, dryRun: Boolean): CliSetupPlan {
        val steps = mutableListOf<CliSetupPlanStep>()
        val refs = mutableMapOf<String, String>()
        val client = cliHttpClient()
        client.use {
            planProfiles(document, dryRun, steps)
            applyResourceGroup(it, "agents", setupResourceSpecs.getValue("agents"), document.resources.agents, refs, steps, dryRun)
            applyResourceGroup(it, "tools", setupResourceSpecs.getValue("tools"), document.resources.tools, refs, steps, dryRun)
            applyResourceGroup(it, "blocks", setupResourceSpecs.getValue("blocks"), document.resources.blocks, refs, steps, dryRun)
            applyResourceGroup(it, "archives", setupResourceSpecs.getValue("archives"), document.resources.archives, refs, steps, dryRun)
            applyResourceGroup(it, "folders", setupResourceSpecs.getValue("folders"), document.resources.folders, refs, steps, dryRun)
            applyResourceGroup(it, "groups", setupResourceSpecs.getValue("groups"), document.resources.groups, refs, steps, dryRun)
            applyResourceGroup(it, "identities", setupResourceSpecs.getValue("identities"), document.resources.identities, refs, steps, dryRun)
            applyResourceGroup(it, "providers", setupResourceSpecs.getValue("providers"), document.resources.providers, refs, steps, dryRun)
            applyResourceGroup(it, "mcpServers", setupResourceSpecs.getValue("mcpServers"), document.resources.mcpServers, refs, steps, dryRun)
            applyResourceGroup(it, "projects", setupResourceSpecs.getValue("projects"), document.resources.projects, refs, steps, dryRun)
            applySchedules(it, document.resources.schedules, refs, steps, dryRun)
            applyLinks(it, document.links, refs, steps, dryRun)
        }
        return CliSetupPlan(steps)
    }

    private fun planProfiles(
        document: CliSetupDocument,
        dryRun: Boolean,
        steps: MutableList<CliSetupPlanStep>,
    ) {
        if (document.profiles.isEmpty() && document.activeProfile == null) return
        val store = CliProfileStore.default()
        document.profiles.forEach { profile ->
            steps += CliSetupPlanStep("upsert-profile", "profile:${profile.name}")
            if (!dryRun) {
                store.upsert(profile, makeActive = document.activeProfile == profile.name)
            }
        }
        if (document.activeProfile != null && !dryRun) {
            store.setActive(document.activeProfile)
        }
        document.activeProfile?.let { steps += CliSetupPlanStep("activate-profile", "profile:$it") }
    }

    private suspend fun applyResourceGroup(
        client: HttpClient,
        group: String,
        spec: SetupResourceSpec,
        resources: List<CliSetupResource>,
        refs: MutableMap<String, String>,
        steps: MutableList<CliSetupPlanStep>,
        dryRun: Boolean,
    ) {
        for (resource in resources) {
            val id = resource.id ?: resource.body.setupId(spec.idFields)
            val target = "$group:${id ?: resource.ref ?: "<new>"}"
            if (resource.ref != null && id != null) refs[resource.ref] = id
            if (dryRun) {
                if (resource.ref != null && id == null) {
                    refs[resource.ref] = "<planned:$group:${resource.ref}>"
                }
                steps += CliSetupPlanStep(if (id == null) "create" else "upsert", target)
                continue
            }
            val action = if (id == null) {
                createResource(client, spec, resource)
            } else {
                upsertResource(client, spec, id, resource)
            }
            val resolvedId = action.second ?: id
            if (resource.ref != null && resolvedId != null) refs[resource.ref] = resolvedId
            steps += CliSetupPlanStep(action.first, target, resolvedId?.let { "id=$it" })
        }
    }

    private suspend fun upsertResource(
        client: HttpClient,
        spec: SetupResourceSpec,
        id: String,
        resource: CliSetupResource,
    ): Pair<String, String?> {
        val get = client.executeJsonRestRequest(
            verb = "GET",
            url = buildRestUrl(baseUrl, buildResourcePathTemplate(spec.itemPath, mapOf("id" to id)), emptyList()),
            token = token,
            body = null,
        )
        val getText = get.bodyAsText()
        return when (get.status.value) {
            in 200..299 -> {
                val body = resource.update ?: resource.body
                val update = client.executeJsonRestRequest(
                    verb = spec.updateVerb,
                    url = buildRestUrl(baseUrl, buildResourcePathTemplate(spec.updatePath, mapOf("id" to id)), emptyList()),
                    token = token,
                    body = setupJsonBody(body),
                )
                val updateText = update.bodyAsText()
                update.ensureSuccess("${spec.name} update $id", updateText)
                "update" to (parseSetupObject(updateText)?.setupId(spec.idFields) ?: id)
            }
            404 -> createResource(client, spec, resource)
            else -> throw UsageError("${spec.name} lookup $id failed: HTTP ${get.status.value} $getText")
        }
    }

    private suspend fun createResource(
        client: HttpClient,
        spec: SetupResourceSpec,
        resource: CliSetupResource,
    ): Pair<String, String?> {
        val body = resource.create ?: resource.body
        if (body.isEmpty()) {
            throw UsageError("${spec.name} create requires body/create JSON when id does not already exist")
        }
        val response = client.executeJsonRestRequest(
            verb = "POST",
            url = buildRestUrl(baseUrl, spec.createPath, emptyList()),
            token = token,
            body = setupJsonBody(body),
        )
        val text = response.bodyAsText()
        response.ensureSuccess("${spec.name} create", text)
        return "create" to parseSetupObject(text)?.setupId(spec.idFields)
    }

    private suspend fun applySchedules(
        client: HttpClient,
        schedules: List<CliSetupSchedule>,
        refs: Map<String, String>,
        steps: MutableList<CliSetupPlanStep>,
        dryRun: Boolean,
    ) {
        schedules.forEach { schedule ->
            val agentId = schedule.agentId ?: schedule.agentRef?.let { refs[it] }
                ?: throw UsageError("schedule ${schedule.ref ?: schedule.id ?: "<new>"} requires agentId or known agentRef")
            if (schedule.id != null && !dryRun) {
                val get = client.executeJsonRestRequest(
                    verb = "GET",
                    url = buildRestUrl(
                        baseUrl,
                        buildResourcePathTemplate(
                            "/v1/agents/{agent_id}/schedule/{schedule_id}",
                            mapOf("agent_id" to agentId, "schedule_id" to schedule.id),
                        ),
                        emptyList(),
                    ),
                    token = token,
                    body = null,
                )
                if (get.status.value in 200..299) {
                    steps += CliSetupPlanStep("exists", "schedule:${schedule.id}", "agent=$agentId")
                    return@forEach
                }
                if (get.status.value != 404) {
                    throw UsageError(
                        "schedule lookup ${schedule.id} failed: HTTP ${get.status.value} ${get.bodyAsText()}"
                    )
                }
            }
            steps += CliSetupPlanStep(if (dryRun) "create" else "create", "schedule:${schedule.id ?: schedule.ref ?: "<new>"}", "agent=$agentId")
            if (!dryRun) {
                val response = client.executeJsonRestRequest(
                    verb = "POST",
                    url = buildRestUrl(
                        baseUrl,
                        buildResourcePathTemplate("/v1/agents/{agent_id}/schedule", mapOf("agent_id" to agentId)),
                        emptyList(),
                    ),
                    token = token,
                    body = setupJsonBody(schedule.body),
                )
                response.ensureSuccess("schedule create", response.bodyAsText())
            }
        }
    }

    private suspend fun applyLinks(
        client: HttpClient,
        links: CliSetupLinks,
        refs: Map<String, String>,
        steps: MutableList<CliSetupPlanStep>,
        dryRun: Boolean,
    ) {
        applyLinkGroup(client, "agent-tool", links.agentTools, "/v1/agents/{agent_id}/tools/attach/{tool_id}", refs, steps, dryRun)
        applyLinkGroup(client, "agent-block", links.agentBlocks, "/v1/agents/{agent_id}/core-memory/blocks/attach/{block_id}", refs, steps, dryRun)
        applyLinkGroup(client, "agent-archive", links.agentArchives, "/v1/agents/{agent_id}/archives/attach/{archive_id}", refs, steps, dryRun)
        applyLinkGroup(client, "agent-identity", links.agentIdentities, "/v1/agents/{agent_id}/identities/attach/{identity_id}", refs, steps, dryRun)
        applyLinkGroup(client, "block-identity", links.blockIdentities, "/v1/blocks/{block_id}/identities/attach/{identity_id}", refs, steps, dryRun)
    }

    private suspend fun applyLinkGroup(
        client: HttpClient,
        name: String,
        links: List<CliSetupLink>,
        pathTemplate: String,
        refs: Map<String, String>,
        steps: MutableList<CliSetupPlanStep>,
        dryRun: Boolean,
    ) {
        links.forEach { link ->
            val values = link.values(refs)
            val target = "$name:${values.values.joinToString(":")}"
            steps += CliSetupPlanStep("attach", target)
            if (!dryRun) {
                val response = client.executeJsonRestRequest(
                    verb = "PATCH",
                    url = buildRestUrl(baseUrl, buildResourcePathTemplate(pathTemplate, values), emptyList()),
                    token = token,
                    body = null,
                )
                response.ensureSuccess("$name attach", response.bodyAsText())
            }
        }
    }
}

private class CliSetupExporter(
    private val baseUrl: String,
    private val token: String,
) {
    suspend fun export(
        profilesOnly: Boolean,
        redactToken: Boolean,
        skipErrors: Boolean,
    ): CliSetupDocument {
        val profiles = CliProfileStore.default().load().let { document ->
            document.copy(profiles = document.profiles.map { if (redactToken) it.copy(token = "<redacted>") else it })
        }
        if (profilesOnly) {
            return CliSetupDocument(activeProfile = profiles.activeProfile, profiles = profiles.profiles)
        }

        val client = cliHttpClient()
        return client.use {
            CliSetupDocument(
                activeProfile = profiles.activeProfile,
                profiles = profiles.profiles,
                resources = CliSetupResources(
                    agents = exportResources(it, setupResourceSpecs.getValue("agents"), skipErrors),
                    tools = exportResources(it, setupResourceSpecs.getValue("tools"), skipErrors),
                    blocks = exportResources(it, setupResourceSpecs.getValue("blocks"), skipErrors),
                    archives = exportResources(it, setupResourceSpecs.getValue("archives"), skipErrors),
                    folders = exportResources(it, setupResourceSpecs.getValue("folders"), skipErrors),
                    groups = exportResources(it, setupResourceSpecs.getValue("groups"), skipErrors),
                    identities = exportResources(it, setupResourceSpecs.getValue("identities"), skipErrors),
                    providers = exportResources(it, setupResourceSpecs.getValue("providers"), skipErrors),
                    mcpServers = exportResources(it, setupResourceSpecs.getValue("mcpServers"), skipErrors),
                    projects = exportResources(it, setupResourceSpecs.getValue("projects"), skipErrors),
                ),
            )
        }
    }

    private suspend fun exportResources(
        client: HttpClient,
        spec: SetupResourceSpec,
        skipErrors: Boolean,
    ): List<CliSetupResource> {
        val response = client.executeJsonRestRequest(
            verb = "GET",
            url = buildRestUrl(baseUrl, spec.listPath, emptyList()),
            token = token,
            body = null,
        )
        val text = response.bodyAsText()
        if (response.status.value !in 200..299) {
            if (skipErrors) return emptyList()
            throw UsageError("${spec.name} export failed: HTTP ${response.status.value} $text")
        }
        val root = parseSetupElement(text)
        return root.asObjectList().map { item ->
            CliSetupResource(
                id = item.setupId(spec.idFields),
                body = item,
            )
        }
    }
}

private data class SetupResourceSpec(
    val name: String,
    val listPath: String,
    val itemPath: String,
    val createPath: String,
    val updatePath: String = itemPath,
    val updateVerb: String = "PATCH",
    val idFields: List<String> = listOf("id"),
)

private val setupResourceSpecs = mapOf(
    "agents" to SetupResourceSpec("agents", "/v1/agents", "/v1/agents/{id}", "/v1/agents"),
    "tools" to SetupResourceSpec("tools", "/v1/tools", "/v1/tools/{id}", "/v1/tools", idFields = listOf("id", "name")),
    "blocks" to SetupResourceSpec("blocks", "/v1/blocks", "/v1/blocks/{id}", "/v1/blocks"),
    "archives" to SetupResourceSpec("archives", "/v1/archives/", "/v1/archives/{id}", "/v1/archives/"),
    "folders" to SetupResourceSpec("folders", "/v1/folders/", "/v1/folders/{id}", "/v1/folders/"),
    "groups" to SetupResourceSpec("groups", "/v1/groups/", "/v1/groups/{id}", "/v1/groups/"),
    "identities" to SetupResourceSpec("identities", "/v1/identities/", "/v1/identities/{id}", "/v1/identities/"),
    "providers" to SetupResourceSpec("providers", "/v1/providers/", "/v1/providers/{id}", "/v1/providers/"),
    "mcpServers" to SetupResourceSpec("mcpServers", "/v1/mcp-servers", "/v1/mcp-servers/{id}", "/v1/mcp-servers"),
    "projects" to SetupResourceSpec(
        name = "projects",
        listPath = "/api/projects",
        itemPath = "/api/projects/{id}",
        createPath = "/api/registry/projects",
        updatePath = "/api/registry/projects/{id}",
        idFields = listOf("identifier", "id"),
    ),
)

private fun CliSetupLink.values(refs: Map<String, String>): Map<String, String> {
    val values = mutableMapOf<String, String>()
    (agentId ?: agentRef?.let { refs[it] })?.let { values["agent_id"] = it }
    (toolId ?: toolRef?.let { refs[it] })?.let { values["tool_id"] = it }
    (blockId ?: blockRef?.let { refs[it] })?.let { values["block_id"] = it }
    (archiveId ?: archiveRef?.let { refs[it] })?.let { values["archive_id"] = it }
    (identityId ?: identityRef?.let { refs[it] })?.let { values["identity_id"] = it }
    if (values.size < 2) {
        throw UsageError("link requires concrete ids or refs resolvable within this setup file: $this")
    }
    return values
}

private fun JsonObject.setupId(fields: List<String>): String? = stringField(*fields.toTypedArray())

private fun setupJsonBody(body: JsonObject): String =
    com.letta.mobile.cli.runtime.CliJson.encodeToString(JsonObject.serializer(), body)

private fun parseSetupElement(text: String): JsonElement =
    com.letta.mobile.cli.runtime.CliJson.parseToJsonElement(text)

private fun parseSetupObject(text: String): JsonObject? =
    runCatching { parseSetupElement(text) as? JsonObject }.getOrNull()

private fun io.ktor.client.statement.HttpResponse.ensureSuccess(label: String, body: String) {
    if (status.value !in 200..299) {
        throw UsageError("$label failed: HTTP ${status.value} $body")
    }
}
