package com.letta.mobile.data.repository.iroh

import com.letta.mobile.data.controller.node.iroh.AdminRpcInvocation
import com.letta.mobile.data.controller.node.iroh.AdminRpcRequestContext
import com.letta.mobile.data.controller.node.iroh.AdminRpcRouter
import com.letta.mobile.data.controller.node.iroh.HostSkillsEnumerator
import com.letta.mobile.data.controller.node.iroh.NativeSkillsCatalog
import com.letta.mobile.data.controller.node.iroh.SkillAdminHandlers
import com.letta.mobile.data.skills.Skill
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * skills-honest-controls, client <-> host: what the wrapper derives from the filesystem (a
 * symlink in letta-code's skills folder is controllable, a real folder is not) reaches the
 * client's [Skill] intact over the real `skill.list` handler and the real admin-rpc decoder.
 */
class SkillsControlContractTest {
    private val dirs = mutableListOf<File>()

    @AfterTest
    fun cleanUp() = dirs.forEach { it.deleteRecursively() }

    private fun dir(): File = Files.createTempDirectory("skills-contract").toFile().also { dirs += it }

    private fun skill(parent: File, name: String) = File(parent, name).apply {
        mkdirs()
        File(this, "SKILL.md").writeText("# $name\n\nAbout $name.\n")
    }

    @Test
    fun hostControllabilityAndSourceReachTheClientSkill() = runTest {
        val control = dir()
        val elsewhere = dir()
        skill(control, "real-folder")
        Files.createSymbolicLink(File(control, "linked").toPath(), skill(elsewhere, "linked").toPath())
        val catalog = NativeSkillsCatalog(hostEnumeration = { HostSkillsEnumerator.enumerate(control, control) })
        val router = AdminRpcRouter().also { SkillAdminHandlers.register(it, skillsListing = catalog.asListingSource()) }

        val transport = FakeIrohAdminTransport()
        transport.rpcResponder = { call -> runBlocking { serve(router, call) } }
        val skills = IrohSkillsApi(IrohAdminRpcAgentDirectory(transport)).listSkills()

        assertEquals(
            listOf(
                Skill(name = "linked", description = "About linked.", source = "global", controllable = true),
                Skill(name = "real-folder", description = "About real-folder.", source = "global", controllable = false),
            ),
            skills,
        )
    }

    private suspend fun serve(router: AdminRpcRouter, call: AdminRpcCall): AppServerInboundFrame.AdminRpcResponse {
        val params = call.body?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
        val frame = Json.parseToJsonElement(
            router.dispatch(AdminRpcInvocation("contract", call.method, params, AdminRpcRequestContext.Authenticated)),
        ).jsonObject
        return AppServerInboundFrame.AdminRpcResponse(
            requestId = "contract",
            success = frame.getValue("success").jsonPrimitive.boolean,
            result = frame["result"],
        )
    }
}
