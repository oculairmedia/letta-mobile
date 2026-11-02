package com.letta.mobile.data.beads

import com.letta.mobile.data.beads.wire.core.AddCommentRequest
import com.letta.mobile.data.beads.wire.payloads.AddDependenciesRequest
import com.letta.mobile.data.beads.wire.payloads.AddDependenciesResponse
import com.letta.mobile.data.beads.wire.payloads.ApplyBatchRequest
import com.letta.mobile.data.beads.wire.payloads.ApplyBatchResponse
import com.letta.mobile.data.beads.wire.mutations.BatchCloseRequest
import com.letta.mobile.data.beads.wire.mutations.BatchCloseResponse
import com.letta.mobile.data.beads.wire.payloads.BatchCreateRequest
import com.letta.mobile.data.beads.wire.payloads.BatchCreateResponse
import com.letta.mobile.data.beads.wire.payloads.BlockingAnnotations
import com.letta.mobile.data.beads.wire.mutations.ClaimNextRequest
import com.letta.mobile.data.beads.wire.mutations.ClaimNextResponse
import com.letta.mobile.data.beads.wire.mutations.ClaimRequest
import com.letta.mobile.data.beads.wire.mutations.ClaimResponse
import com.letta.mobile.data.beads.wire.mutations.CloseIssueRequest
import com.letta.mobile.data.beads.wire.mutations.CloseIssueResponse
import com.letta.mobile.data.beads.wire.core.Comment
import com.letta.mobile.data.beads.wire.mutations.CompareAndSetMetadataRequest
import com.letta.mobile.data.beads.wire.mutations.CompareAndSetMetadataResponse
import com.letta.mobile.data.beads.wire.core.ContextResponse
import com.letta.mobile.data.beads.wire.mutations.CreateIssueRequest
import com.letta.mobile.data.beads.wire.core.CyclesPage
import com.letta.mobile.data.beads.wire.core.DeleteIssuesRequest
import com.letta.mobile.data.beads.wire.core.DeleteIssuesResult
import com.letta.mobile.data.beads.wire.payloads.DependencyEdges
import com.letta.mobile.data.beads.wire.core.DependencyTreePage
import com.letta.mobile.data.beads.wire.payloads.EdgeCounts
import com.letta.mobile.data.beads.wire.core.EventRecord
import com.letta.mobile.data.beads.wire.core.EventsPage
import com.letta.mobile.data.beads.wire.core.Health
import com.letta.mobile.data.beads.wire.core.Issue
import com.letta.mobile.data.beads.wire.core.IssueCount
import com.letta.mobile.data.beads.wire.core.IssueDetails
import com.letta.mobile.data.beads.wire.core.IssuesPage
import com.letta.mobile.data.beads.wire.core.MemoriesPage
import com.letta.mobile.data.beads.wire.core.Memory
import com.letta.mobile.data.beads.wire.core.QueryPage
import com.letta.mobile.data.beads.wire.core.ReadyCount
import com.letta.mobile.data.beads.wire.core.ReadyPage
import com.letta.mobile.data.beads.wire.payloads.RelatedIssues
import com.letta.mobile.data.beads.wire.mutations.ReleaseIssueRequest
import com.letta.mobile.data.beads.wire.mutations.ReleaseIssueResponse
import com.letta.mobile.data.beads.wire.payloads.RememberRequest
import com.letta.mobile.data.beads.wire.payloads.RememberedMemory
import com.letta.mobile.data.beads.wire.payloads.RemoveDependencyRequest
import com.letta.mobile.data.beads.wire.payloads.RemoveDependencyResponse
import com.letta.mobile.data.beads.wire.payloads.RemovedSetting
import com.letta.mobile.data.beads.wire.mutations.ReopenIssueRequest
import com.letta.mobile.data.beads.wire.mutations.ReopenIssueResponse
import com.letta.mobile.data.beads.wire.payloads.SetSettingRequest
import com.letta.mobile.data.beads.wire.core.Setting
import com.letta.mobile.data.beads.wire.payloads.SettingsPage
import com.letta.mobile.data.beads.wire.payloads.StatsResponse
import com.letta.mobile.data.beads.wire.core.SweepRequest
import com.letta.mobile.data.beads.wire.core.SweepResult
import com.letta.mobile.data.beads.wire.mutations.UpdateIssueRequest
import com.letta.mobile.data.beads.wire.mutations.UpdateIssueResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonElement
import com.letta.mobile.data.serialization.LettaJson
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** A journal record delivered by the events watch stream. */
typealias BeadsEvent = EventRecord

/** Operations supported by the pinned bd serve HTTP contract. */
@Suppress("TooManyFunctions")
interface BeadsBackend {
    /** Identity and capabilities received during the connection handshake. */
    val context: ContextResponse

    /** Executes a pinned operation with its wire parameters. */
    suspend fun execute(
        operation: BeadsOperation,
        pathParameters: Map<String, String> = emptyMap(),
        query: Map<String, List<String>> = emptyMap(),
        body: JsonElement? = null,
    ): JsonElement

    /** Calls the health operation. */
    suspend fun health(): Health =
        decodeBeads(execute(BeadsOperation.HEALTH, emptyMap(), emptyMap(), null))

    /** Calls the getContext operation. */
    suspend fun fetchContext(): ContextResponse =
        decodeBeads(execute(BeadsOperation.GET_CONTEXT, emptyMap(), emptyMap(), null))

    /** Calls the listReadyWork operation. */
    suspend fun listReadyWork(query: Map<String, List<String>> = emptyMap()): ReadyPage =
        decodeBeads(execute(BeadsOperation.LIST_READY_WORK, emptyMap(), query, null))

    /** Calls the getStats operation. */
    suspend fun getStats(query: Map<String, List<String>> = emptyMap()): StatsResponse =
        decodeBeads(execute(BeadsOperation.GET_STATS, emptyMap(), query, null))

    /** Calls the listDependencyCycles operation. */
    suspend fun listDependencyCycles(query: Map<String, List<String>> = emptyMap()): CyclesPage =
        decodeBeads(execute(BeadsOperation.LIST_DEPENDENCY_CYCLES, emptyMap(), query, null))

    /** Calls the getDependencyTree operation. */
    suspend fun getDependencyTree(rootId: BeadsIssueId, query: Map<String, List<String>> = emptyMap()): DependencyTreePage =
        decodeBeads(execute(BeadsOperation.GET_DEPENDENCY_TREE, emptyMap(), query + mapOf("root_id" to listOf(rootId.value)), null))

    /** Calls the countReadyWork operation. */
    suspend fun countReadyWork(query: Map<String, List<String>> = emptyMap()): ReadyCount =
        decodeBeads(execute(BeadsOperation.COUNT_READY_WORK, emptyMap(), query, null))

    /** Calls the listIssues operation. */
    suspend fun listIssues(query: Map<String, List<String>> = emptyMap()): IssuesPage =
        decodeBeads(execute(BeadsOperation.LIST_ISSUES, emptyMap(), query, null))

    /** Calls the createIssue operation. */
    suspend fun createIssue(body: CreateIssueRequest): Issue =
        decodeBeads(execute(BeadsOperation.CREATE_ISSUE, emptyMap(), emptyMap(), wireJson.encodeToJsonElement(body)))

    /** Calls the queryIssues operation. */
    suspend fun queryIssues(q: String, query: Map<String, List<String>> = emptyMap()): QueryPage =
        decodeBeads(execute(BeadsOperation.QUERY_ISSUES, emptyMap(), query + mapOf("q" to listOf(q)), null))

    /** Calls the countIssues operation. */
    suspend fun countIssues(query: Map<String, List<String>> = emptyMap()): IssueCount =
        decodeBeads(execute(BeadsOperation.COUNT_ISSUES, emptyMap(), query, null))

    /** Calls the getIssue operation. */
    suspend fun getIssue(id: BeadsIssueId, query: Map<String, List<String>> = emptyMap()): IssueDetails =
        decodeBeads(execute(BeadsOperation.GET_ISSUE, mapOf("id" to id.value), query, null))

    /** Calls the updateIssue operation. */
    suspend fun updateIssue(id: BeadsIssueId, body: UpdateIssueRequest): UpdateIssueResponse =
        decodeBeads(execute(BeadsOperation.UPDATE_ISSUE, mapOf("id" to id.value), emptyMap(), wireJson.encodeToJsonElement(body)))

    /** Calls the listRelatedIssues operation. */
    suspend fun listRelatedIssues(id: BeadsIssueId, direction: String, query: Map<String, List<String>> = emptyMap()): RelatedIssues =
        decodeBeads(
            execute(
                BeadsOperation.LIST_RELATED_ISSUES,
                mapOf("id" to id.value),
                query + mapOf("direction" to listOf(direction)),
                null,
            ),
        )

    /** Calls the addComment operation. */
    suspend fun addComment(id: BeadsIssueId, body: AddCommentRequest): Comment =
        decodeBeads(execute(BeadsOperation.ADD_COMMENT, mapOf("id" to id.value), emptyMap(), wireJson.encodeToJsonElement(body)))

    /** Calls the claimIssue operation. */
    suspend fun claimIssue(id: BeadsIssueId, body: ClaimRequest): ClaimResponse =
        decodeBeads(execute(BeadsOperation.CLAIM_ISSUE, mapOf("id" to id.value), emptyMap(), wireJson.encodeToJsonElement(body)))

    /** Calls the releaseIssue operation. */
    suspend fun releaseIssue(id: BeadsIssueId, body: ReleaseIssueRequest): ReleaseIssueResponse =
        decodeBeads(execute(BeadsOperation.RELEASE_ISSUE, mapOf("id" to id.value), emptyMap(), wireJson.encodeToJsonElement(body)))

    /** Calls the closeIssue operation. */
    suspend fun closeIssue(id: BeadsIssueId, body: CloseIssueRequest): CloseIssueResponse =
        decodeBeads(execute(BeadsOperation.CLOSE_ISSUE, mapOf("id" to id.value), emptyMap(), wireJson.encodeToJsonElement(body)))

    /** Calls the reopenIssue operation. */
    suspend fun reopenIssue(id: BeadsIssueId, body: ReopenIssueRequest): ReopenIssueResponse =
        decodeBeads(execute(BeadsOperation.REOPEN_ISSUE, mapOf("id" to id.value), emptyMap(), wireJson.encodeToJsonElement(body)))

    /** Calls the compareAndSetMetadata operation. */
    suspend fun compareAndSetMetadata(id: BeadsIssueId, body: CompareAndSetMetadataRequest): CompareAndSetMetadataResponse =
        decodeBeads(
            execute(
                BeadsOperation.COMPARE_AND_SET_METADATA,
                mapOf("id" to id.value),
                emptyMap(),
                wireJson.encodeToJsonElement(body),
            ),
        )

    /** Calls the sweepIssues operation. */
    suspend fun sweepIssues(body: SweepRequest): SweepResult =
        decodeBeads(execute(BeadsOperation.SWEEP_ISSUES, emptyMap(), emptyMap(), wireJson.encodeToJsonElement(body)))

    /** Calls the deleteIssues operation. */
    suspend fun deleteIssues(body: DeleteIssuesRequest): DeleteIssuesResult =
        decodeBeads(execute(BeadsOperation.DELETE_ISSUES, emptyMap(), emptyMap(), wireJson.encodeToJsonElement(body)))

    /** Calls the batchCreateIssues operation. */
    suspend fun batchCreateIssues(body: BatchCreateRequest): BatchCreateResponse =
        decodeBeads(execute(BeadsOperation.BATCH_CREATE_ISSUES, emptyMap(), emptyMap(), wireJson.encodeToJsonElement(body)))

    /** Calls the applyBatch operation. */
    suspend fun applyBatch(body: ApplyBatchRequest): ApplyBatchResponse =
        decodeBeads(execute(BeadsOperation.APPLY_BATCH, emptyMap(), emptyMap(), wireJson.encodeToJsonElement(body)))

    /** Calls the batchCloseIssues operation. */
    suspend fun batchCloseIssues(body: BatchCloseRequest): BatchCloseResponse =
        decodeBeads(execute(BeadsOperation.BATCH_CLOSE_ISSUES, emptyMap(), emptyMap(), wireJson.encodeToJsonElement(body)))

    /** Calls the claimNextIssue operation. */
    suspend fun claimNextIssue(body: ClaimNextRequest, query: Map<String, List<String>> = emptyMap()): ClaimNextResponse =
        decodeBeads(execute(BeadsOperation.CLAIM_NEXT_ISSUE, emptyMap(), query, wireJson.encodeToJsonElement(body)))

    /** Calls the listSettings operation. */
    suspend fun listSettings(query: Map<String, List<String>> = emptyMap()): SettingsPage =
        decodeBeads(execute(BeadsOperation.LIST_SETTINGS, emptyMap(), query, null))

    /** Calls the getSetting operation. */
    suspend fun getSetting(key: String, query: Map<String, List<String>> = emptyMap()): Setting =
        decodeBeads(execute(BeadsOperation.GET_SETTING, mapOf("key" to key), query, null))

    /** Calls the setSetting operation. */
    suspend fun setSetting(key: String, body: SetSettingRequest): Setting =
        decodeBeads(execute(BeadsOperation.SET_SETTING, mapOf("key" to key), emptyMap(), wireJson.encodeToJsonElement(body)))

    /** Calls the unsetSetting operation. */
    suspend fun unsetSetting(key: String): RemovedSetting =
        decodeBeads(execute(BeadsOperation.UNSET_SETTING, mapOf("key" to key), emptyMap(), null))

    /** Calls the listDependencies operation. */
    suspend fun listDependencies(issueId: BeadsIssueId, query: Map<String, List<String>> = emptyMap()): DependencyEdges =
        decodeBeads(execute(BeadsOperation.LIST_DEPENDENCIES, emptyMap(), query + mapOf("issue_id" to listOf(issueId.value)), null))

    /** Calls the countDependencyEdges operation. */
    suspend fun countDependencyEdges(issueId: BeadsIssueId, direction: String, query: Map<String, List<String>> = emptyMap()): EdgeCounts =
        decodeBeads(
            execute(
                BeadsOperation.COUNT_DEPENDENCY_EDGES,
                emptyMap(),
                query + mapOf("issue_id" to listOf(issueId.value), "direction" to listOf(direction)),
                null,
            ),
        )

    /** Calls the listBlockingAnnotations operation. */
    suspend fun listBlockingAnnotations(issueId: BeadsIssueId, query: Map<String, List<String>> = emptyMap()): BlockingAnnotations =
        decodeBeads(execute(BeadsOperation.LIST_BLOCKING_ANNOTATIONS, emptyMap(), query + mapOf("issue_id" to listOf(issueId.value)), null))

    /** Calls the addDependencies operation. */
    suspend fun addDependencies(body: AddDependenciesRequest): AddDependenciesResponse =
        decodeBeads(execute(BeadsOperation.ADD_DEPENDENCIES, emptyMap(), emptyMap(), wireJson.encodeToJsonElement(body)))

    /** Calls the removeDependency operation. */
    suspend fun removeDependency(body: RemoveDependencyRequest): RemoveDependencyResponse =
        decodeBeads(execute(BeadsOperation.REMOVE_DEPENDENCY, emptyMap(), emptyMap(), wireJson.encodeToJsonElement(body)))

    /** Calls the listMemories operation. */
    suspend fun listMemories(query: Map<String, List<String>> = emptyMap()): MemoriesPage =
        decodeBeads(execute(BeadsOperation.LIST_MEMORIES, emptyMap(), query, null))

    /** Calls the rememberMemory operation. */
    suspend fun rememberMemory(body: RememberRequest): RememberedMemory =
        decodeBeads(execute(BeadsOperation.REMEMBER_MEMORY, emptyMap(), emptyMap(), wireJson.encodeToJsonElement(body)))

    /** Calls the getMemory operation. */
    suspend fun getMemory(key: String, query: Map<String, List<String>> = emptyMap()): Memory =
        decodeBeads(execute(BeadsOperation.GET_MEMORY, mapOf("key" to key), query, null))

    /** Calls the forgetMemory operation. */
    suspend fun forgetMemory(key: String): Memory =
        decodeBeads(execute(BeadsOperation.FORGET_MEMORY, mapOf("key" to key), emptyMap(), null))

    /** Calls the listEvents operation. */
    suspend fun listEvents(since: Long, query: Map<String, List<String>> = emptyMap()): EventsPage =
        decodeBeads(execute(BeadsOperation.LIST_EVENTS, emptyMap(), query + mapOf("since" to listOf(since.toString())), null))

    /** Watches the journal from the given checkpoint. */
    fun watchEvents(since: Long, lastEventId: Long? = null): Flow<BeadsEvent>

}

internal val wireJson = LettaJson.wire

internal inline fun <reified T> decodeBeads(value: JsonElement): T = try {
    wireJson.decodeFromJsonElement(value)
} catch (_: Exception) {
    throw BeadsProtocolException()
}
