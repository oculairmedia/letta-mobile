package com.letta.mobile.data.plugin.view

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Every view message, well formed and not (letta-mobile-s416w.31). */
class ViewMessageValidatorTest {
    private fun call(raw: String): ViewInbound.Call = assertIs<ViewInbound.Call>(ViewMessageValidator.validate(raw), raw)

    private fun refused(raw: String, code: ViewErrorCode): ViewInbound.Refused {
        val refused = assertIs<ViewInbound.Refused>(ViewMessageValidator.validate(raw), raw)
        assertEquals(code, refused.error.code, raw)
        return refused
    }

    private fun request(method: String, params: String, id: String = "1") = """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}"""

    private fun notification(method: String, params: String) = """{"jsonrpc":"2.0","method":"$method","params":$params}"""

    @Test
    fun everyMethodWithGoodParamsIsACall() {
        val good = listOf(
            request("view.ready", """{"pageId":"widget","viewVersion":"1"}"""),
            request("view.action", """{"action":"start","input":{"label":"x"}}"""),
            request("view.action", """{"action":"refresh"}"""),
            notification("view.resize", """{"width":320,"height":240.5}"""),
            request("view.displayMode", """{"mode":"fullscreen"}"""),
            request("view.openLink", """{"url":"https://example.test/a"}"""),
            notification("view.log", """{"level":"warn","message":""}"""),
        )
        val methods = good.map { call(it).method }
        assertEquals(ViewMethod.entries.toList(), methods.distinct())
    }

    @Test
    fun idsAreShortStringsOrIntegers() {
        assertEquals(JsonPrimitive("a-1"), call(request("view.displayMode", """{"mode":"inline"}""", "\"a-1\"")).id)
        assertEquals(JsonPrimitive(7), call(request("view.displayMode", """{"mode":"inline"}""", "7")).id)
        listOf("true", "1.5", "null", "{}", "[]", "\"\"", "\"${"x".repeat(LcpView.MAX_ID_LENGTH + 1)}\"").forEach { id ->
            refused(request("view.displayMode", """{"mode":"inline"}""", id), ViewErrorCode.INVALID_REQUEST)
        }
    }

    @Test
    fun omittedParamsAreAnEmptyObject() {
        val raw = """{"jsonrpc":"2.0","id":1,"method":"view.action"}"""
        val refusal = refused(raw, ViewErrorCode.INVALID_PARAMS)
        assertEquals("/params/action", refusal.error.data!!.jsonObject["problems"]!!.jsonArray.single().jsonObject["path"]!!.jsonPrimitive.content)
    }

    @Test
    fun brokenEnvelopesAreInvalidRequests() {
        refused("[1,2]", ViewErrorCode.INVALID_REQUEST)
        refused("""{"id":1,"method":"view.ready","params":{}}""", ViewErrorCode.INVALID_REQUEST)
        refused("""{"jsonrpc":"1.0","id":1,"method":"view.ready","params":{}}""", ViewErrorCode.INVALID_REQUEST)
        refused("""{"jsonrpc":"2.0","id":1,"method":"view.displayMode","params":{"mode":"inline"},"extra":1}""", ViewErrorCode.INVALID_REQUEST)
        refused("""{"jsonrpc":"2.0","id":1,"method":5}""", ViewErrorCode.INVALID_REQUEST)
        refused("""{"jsonrpc":"2.0","id":1,"method":"view.displayMode","params":{"mode":"inline"},"result":{}}""", ViewErrorCode.INVALID_REQUEST)
        refused("""{"jsonrpc":"2.0"}""", ViewErrorCode.INVALID_REQUEST)
    }

    @Test
    fun notJsonIsAParseErrorWithoutAnId() {
        assertNull(refused("{\"jsonrpc\":", ViewErrorCode.PARSE_ERROR).id)
        refused("", ViewErrorCode.PARSE_ERROR)
    }

    @Test
    fun overTheCapIsRefusedBeforeParsing() {
        val padding = "x".repeat(LcpView.MAX_MESSAGE_BYTES)
        refused(notification("view.log", """{"level":"info","message":"$padding"}"""), ViewErrorCode.TOO_LARGE)
        // Multi-byte characters count as their UTF-8 bytes.
        refused("\"" + "é".repeat(LcpView.MAX_MESSAGE_BYTES / 2 + 1) + "\"", ViewErrorCode.TOO_LARGE)
    }

    @Test
    fun unknownAndHostMethodsAreNotFound() {
        listOf("view.eval", "host.context", "host.teardown", "ui/initialize", "tools/call").forEach { method ->
            val refusal = refused(request(method, "{}"), ViewErrorCode.METHOD_NOT_FOUND)
            assertEquals(method, refusal.method)
            assertEquals(JsonPrimitive(1), refusal.id)
        }
    }

    @Test
    fun requestsNeedAnIdAndNotificationsHaveNone() {
        refused(notification("view.ready", """{"pageId":"widget","viewVersion":"1"}"""), ViewErrorCode.INVALID_REQUEST)
        refused(notification("view.action", """{"action":"start"}"""), ViewErrorCode.INVALID_REQUEST)
        refused(request("view.resize", """{"width":1,"height":1}"""), ViewErrorCode.INVALID_REQUEST)
        refused(request("view.log", """{"level":"info","message":"m"}"""), ViewErrorCode.INVALID_REQUEST)
    }

    @Test
    fun badParamsAreRefusedAtTheirPointers() {
        val cases = mapOf(
            request("view.ready", """{"pageId":"widget"}""") to "/params/viewVersion",
            request("view.ready", """{"pageId":"","viewVersion":"1"}""") to "/params/pageId",
            request("view.action", """{"action":"start","input":[]}""") to "/params/input",
            request("view.action", """{"action":"start","run":true}""") to "/params/run",
            notification("view.resize", """{"width":0,"height":10}""") to "/params/width",
            notification("view.resize", """{"width":10,"height":10001}""") to "/params/height",
            notification("view.resize", """{"width":"10","height":10}""") to "/params/width",
            request("view.displayMode", """{"mode":"maximized"}""") to "/params/mode",
            request("view.openLink", """{"url":"${"u".repeat(2049)}"}""") to "/params/url",
            notification("view.log", """{"level":"fatal","message":"m"}""") to "/params/level",
            notification("view.log", """{"level":"info","message":"${"m".repeat(2001)}"}""") to "/params/message",
        )
        cases.forEach { (raw, path) ->
            val problems = refused(raw, ViewErrorCode.INVALID_PARAMS).error.data!!.jsonObject["problems"]!!.jsonArray
            assertEquals(path, problems.first().jsonObject["path"]!!.jsonPrimitive.content, raw)
        }
        refused(request("view.displayMode", "[\"inline\"]"), ViewErrorCode.INVALID_PARAMS)
    }

    @Test
    fun answersCarryAnIdAndExactlyOneOfResultOrError() {
        val reply = assertIs<ViewInbound.Reply>(ViewMessageValidator.validate("""{"jsonrpc":"2.0","id":"host-1","result":{}}"""))
        assertEquals(JsonPrimitive("host-1"), reply.id)
        assertIs<ViewInbound.Reply>(ViewMessageValidator.validate("""{"jsonrpc":"2.0","id":"host-1","error":{"code":1,"message":"m"}}"""))
        refused("""{"jsonrpc":"2.0","result":{}}""", ViewErrorCode.INVALID_REQUEST)
        refused("""{"jsonrpc":"2.0","id":"host-1","result":{},"error":{}}""", ViewErrorCode.INVALID_REQUEST)
        refused("""{"jsonrpc":"2.0","id":"host-1","error":"boom"}""", ViewErrorCode.INVALID_REQUEST)
    }
}
