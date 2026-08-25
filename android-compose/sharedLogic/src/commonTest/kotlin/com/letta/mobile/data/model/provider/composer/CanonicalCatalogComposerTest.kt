package com.letta.mobile.data.model.provider.composer

import com.letta.mobile.data.model.HostId
import com.letta.mobile.data.model.ModelRouteId
import com.letta.mobile.data.model.ProviderDefinitionId
import com.letta.mobile.data.model.ProviderInstanceId
import com.letta.mobile.data.model.provider.CanonicalModelRoute
import com.letta.mobile.data.model.provider.CredentialStatus
import com.letta.mobile.data.model.provider.ProviderDefinition
import com.letta.mobile.data.model.provider.ProviderProtocol
import com.letta.mobile.data.model.provider.RedactedProviderInstance
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CanonicalCatalogComposerTest {
    private val host = HostId("host-primary")

    @Test
    fun providerBrandProvenanceKeepsBrandsWithSharedProtocolDistinct() {
        val protocol = persistentListOf(ProviderProtocol.OpenAi)
        val firstDefinition = ProviderDefinition(ProviderDefinitionId("official"), "Official", supportedProtocols = protocol)
        val secondDefinition = ProviderDefinition(ProviderDefinitionId("gateway"), "Gateway", supportedProtocols = protocol)
        val first = provider("first", firstDefinition.id, "Official account")
        val second = provider("second", secondDefinition.id, "Gateway account")

        val result = compose(
            routes = persistentListOf(route("r1", first.id, "official/model"), route("r2", second.id, "gateway/model")),
            providers = persistentListOf(first, second),
            definitions = persistentMapOf(firstDefinition.id to firstDefinition, secondDefinition.id to secondDefinition),
        )

        assertEquals(setOf(firstDefinition.id, secondDefinition.id), result.routes.mapNotNull { it.providerDefinitionId }.toSet())
        assertTrue(result.routes.all { it.supportedProtocols == protocol })
    }

    @Test
    fun authoritativeAliasCollapsesRouteAndPreservesSavedLegacySelection() {
        val canonical = route("canonical", ProviderInstanceId("provider"), "openai/MiniMax-M3")
        val legacy = route("legacy", ProviderInstanceId("provider"), "lmstudio/MiniMax-M3")
        val result = CanonicalCatalogComposer.compose(
            CatalogComposerInput(
                activeHostId = host,
                modelRoutes = persistentListOf(legacy, canonical),
                aliasBindings = persistentListOf(CatalogAliasBinding(canonical.id, legacy.id, legacy.modelHandle)),
                selectedIdentity = legacy.modelHandle,
            ),
        )

        assertEquals(1, result.routes.size)
        assertEquals(canonical.id, result.routes.single().id)
        assertEquals(listOf(legacy.modelHandle), result.routes.single().aliases)
        assertEquals(SelectionResolution.Resolved(canonical.id, legacy.modelHandle), result.selection)
    }

    @Test
    fun shuffledInputsProduceCompleteIdenticalProjection() {
        val definitions = (1..3).associate { index ->
            val id = ProviderDefinitionId("definition-$index")
            id to ProviderDefinition(id, "Brand $index", supportedProtocols = persistentListOf(ProviderProtocol.OpenAi))
        }
        val providers = (1..3).map { provider("provider-$it", ProviderDefinitionId("definition-$it"), "Provider") }
        val routes = (1..12).map { index ->
            route("route-$index", providers[index % providers.size].id, "handle-$index", "Model")
        }
        val normal = CatalogComposerInput(
            activeHostId = host,
            modelRoutes = routes.toPersistentList(),
            providerInstances = providers.toPersistentList(),
            providerDefinitions = definitions.toPersistentMap(),
        )
        val reversed = normal.copy(
            modelRoutes = routes.reversed().toPersistentList(),
            providerInstances = providers.reversed().toPersistentList(),
            providerDefinitions = definitions.entries.reversed().associate { it.toPair() }.toPersistentMap(),
        )

        assertEquals(CanonicalCatalogComposer.compose(normal), CanonicalCatalogComposer.compose(reversed))
    }

    @Test
    fun typedIdentityCollisionsAreRejectedIndependentOfOrder() {
        val duplicateRoutes = listOf(
            route("duplicate", ProviderInstanceId("provider-a"), "a"),
            route("duplicate", ProviderInstanceId("provider-b"), "b"),
        )
        duplicateRoutes.indices.forEach { offset ->
            assertFailsWith<IllegalArgumentException> {
                compose(duplicateRoutes.drop(offset).plus(duplicateRoutes.take(offset)).toPersistentList())
            }
        }

        val duplicateProviders = persistentListOf(
            provider("duplicate", ProviderDefinitionId("a"), "A"),
            provider("duplicate", ProviderDefinitionId("b"), "B"),
        )
        assertFailsWith<IllegalArgumentException> {
            compose(persistentListOf(route("r", ProviderInstanceId("duplicate"), "m")), duplicateProviders)
        }
    }

    @Test
    fun ambiguousSelectionTokensAreRejectedRatherThanOrderResolved() {
        val routes = persistentListOf(
            route("one", ProviderInstanceId("a"), "shared"),
            route("two", ProviderInstanceId("b"), "shared"),
        )
        assertFailsWith<IllegalArgumentException> { compose(routes) }
        assertFailsWith<IllegalArgumentException> { compose(routes.reversed().toPersistentList()) }
    }

    @Test
    fun previousProjectionReuseIsReferentialAndScopeIsolated() {
        val routes = persistentListOf(route("r", ProviderInstanceId("provider"), "model"))
        val firstInput = CatalogComposerInput(
            activeHostId = host,
            modelRoutes = routes,
            accountScopeId = CatalogAccountScopeId("account-a"),
            sessionScopeId = CatalogSessionScopeId("session-a"),
        )
        val first = CanonicalCatalogComposer.compose(firstInput)
        val reused = CanonicalCatalogComposer.compose(firstInput.copy(previousProjection = first))
        val otherAccount = CanonicalCatalogComposer.compose(
            firstInput.copy(accountScopeId = CatalogAccountScopeId("account-b"), previousProjection = first),
        )
        val otherSession = CanonicalCatalogComposer.compose(
            firstInput.copy(sessionScopeId = CatalogSessionScopeId("session-b"), previousProjection = first),
        )

        assertSame(first, reused)
        assertNotSame(first, otherAccount)
        assertNotSame(first, otherSession)
        assertEquals(CatalogAccountScopeId("account-b"), otherAccount.scope.accountId)
        assertEquals(CatalogSessionScopeId("session-b"), otherSession.scope.sessionId)
    }

    @Test
    fun emptyNullAndUnresolvedSelectionBoundariesAreExplicit() {
        val empty = CanonicalCatalogComposer.compose(CatalogComposerInput(host, persistentListOf()))
        assertTrue(empty.routes.isEmpty())
        assertEquals(SelectionResolution.None, empty.selection)

        val unresolved = CanonicalCatalogComposer.compose(
            CatalogComposerInput(host, persistentListOf(route("r", ProviderInstanceId("p"), "known")), selectedIdentity = "missing"),
        )
        assertEquals(SelectionResolution.Unresolved, unresolved.selection)
        assertFailsWith<IllegalArgumentException> {
            CanonicalCatalogComposer.compose(CatalogComposerInput(host, persistentListOf(route("r", ProviderInstanceId("p"), ""))))
        }
    }

    @Test
    fun projectionSnapshotCannotContainProviderConfigurationValues() {
        val definition = ProviderDefinition(ProviderDefinitionId("definition"), "Brand")
        val provider = RedactedProviderInstance(
            id = ProviderInstanceId("provider"),
            hostId = host,
            definitionId = definition.id,
            displayName = "Account",
            baseUrl = "https://endpoint.example/v1",
            credentialStatus = CredentialStatus.Configured,
            configuredHeaderNames = persistentListOf("Authorization"),
        )
        val result = compose(
            persistentListOf(route("r", provider.id, "model")),
            persistentListOf(provider),
            persistentMapOf(definition.id to definition),
        )
        val snapshot = Json.encodeToString(EffectiveCatalogProjection.serializer(), result)

        assertTrue("endpoint.example" !in snapshot)
        assertTrue("Authorization" !in snapshot)
        assertTrue("credential" !in snapshot.lowercase())
    }

    private fun compose(
        routes: kotlinx.collections.immutable.ImmutableList<CanonicalModelRoute>,
        providers: kotlinx.collections.immutable.ImmutableList<RedactedProviderInstance> = persistentListOf(),
        definitions: kotlinx.collections.immutable.ImmutableMap<ProviderDefinitionId, ProviderDefinition> = persistentMapOf(),
    ) = CanonicalCatalogComposer.compose(
        CatalogComposerInput(host, routes, providerInstances = providers, providerDefinitions = definitions),
    )

    private fun route(
        id: String,
        providerId: ProviderInstanceId,
        handle: String,
        displayName: String = id,
    ) = CanonicalModelRoute(ModelRouteId(id), host, providerId, handle, displayName)

    private fun provider(
        id: String,
        definitionId: ProviderDefinitionId,
        displayName: String,
    ) = RedactedProviderInstance(ProviderInstanceId(id), host, definitionId, displayName)
}
