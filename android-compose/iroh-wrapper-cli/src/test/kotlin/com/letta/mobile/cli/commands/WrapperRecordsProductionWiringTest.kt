package com.letta.mobile.cli.commands

import com.github.ajalt.clikt.parameters.options.OptionWithValues
import com.letta.mobile.data.controller.DefaultAppServerController
import com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.util.Telemetry
import com.letta.mobile.util.TelemetryRecorder
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.emptyFlow
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WrapperRecordsProductionWiringTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private val controllers = mutableListOf<DefaultAppServerController>()

    @AfterEach
    fun closeControllers() { controllers.forEach { it.close() } }

    private fun controller(): DefaultAppServerController {
        val native = Proxy.newProxyInstance(AppServerClient::class.java.classLoader, arrayOf(AppServerClient::class.java)) {
            _, method, _ ->
            check(method.name == "getEvents") { "Unexpected App Server call: ${method.name}" }
            emptyFlow<AppServerReceivedFrame>()
        } as AppServerClient
        return DefaultAppServerController(native).also(controllers::add)
    }

    @Test
    fun explicitStorePathPersistsAndDefaultLivesBesideModelExposure() = runTest {
        val explicit = temporaryDirectory.resolve("explicit.json")
        val model = temporaryDirectory.resolve("model-exposure.json")
        val router = buildProductionAdminRouter(controller(), runRecordsFile = explicit.toString(), modelExposureFile = model.toString())
        val params = buildJsonObject {
            put("id", "lease-1"); put("owner", "agent-A"); put("label", "work")
            put("startedAt", "2026-11-01T00:00:00Z"); put("dueAt", "2026-11-03T00:00:00Z"); put("state", "active")
        }
        val written = Json.parseToJsonElement(router.dispatch(AdminRpcInvocation("write", "lease.upsert", params))).jsonObject
        assertTrue(written.getValue("success").jsonPrimitive.boolean, "$written")
        assertTrue(Files.isRegularFile(explicit))
        val reopened = buildProductionAdminRouter(controller(), runRecordsFile = explicit.toString(), modelExposureFile = model.toString())
        val frame = reopened.dispatch(AdminRpcInvocation("read", "lease.list", JsonObject(emptyMap())))
        val response = Json.parseToJsonElement(frame).jsonObject
        val row = response.getValue("result").jsonObject.getValue("leases").jsonArray.single().jsonObject
        assertEquals("lease-1", row.getValue("id").jsonPrimitive.content)
        val default = buildProductionAdminRouter(controller(), modelExposureFile = model.toString())
        default.dispatch(AdminRpcInvocation("default", "lease.upsert", params))
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("run-records.json")))
    }

    @Test
    fun corruptProductionStoreWarnsWithoutBodyAndServesEmpty() = runTest {
        val path = temporaryDirectory.resolve("corrupt.json")
        Files.writeString(path, "{CANARY-PRIVATE-DETAIL")
        val recorder = TelemetryRecorder()
        Telemetry.installRecorder(recorder).use {
            val router = buildProductionAdminRouter(controller(), runRecordsFile = path.toString())
            assertTrue(recorder.snapshot().any { it.tag == "WrapperRecords" && it.name == "store.corrupt" &&
                it.level == Telemetry.Level.WARN })
            assertTrue(recorder.snapshot().none { it.toString().contains("CANARY-PRIVATE-DETAIL") })
            val frame = router.dispatch(AdminRpcInvocation("read", "lease.list", JsonObject(emptyMap())))
            val result = Json.parseToJsonElement(frame).jsonObject.getValue("result").jsonObject
            assertTrue(result.getValue("leases").jsonArray.isEmpty())
        }
    }

    @Test
    fun unconfiguredRouterConstructionLeavesSharedHostStateUntouched() {
        val previousHome = requireNotNull(System.getProperty("user.home"))
        val file = temporaryDirectory.resolve(".letta/canvas-relay/run-records.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, "{shared-state-canary")
        System.setProperty("user.home", temporaryDirectory.toString())
        try {
            buildProductionAdminRouter(controller())
            assertTrue(Files.exists(file), "an unconfigured factory must not quarantine shared host state")
            assertEquals("{shared-state-canary", Files.readString(file))
        } finally {
            System.setProperty("user.home", previousHome)
        }
    }

    @Test
    fun optionAndEnvironmentVariableAreRegistered() {
        val option = AppServerServeIrohCommand().registeredOptions().single { "--run-records-file" in it.names }
        assertEquals("LETTA_RUN_RECORDS_STORE", (option as OptionWithValues<*, *, *>).envvar)
    }
}
