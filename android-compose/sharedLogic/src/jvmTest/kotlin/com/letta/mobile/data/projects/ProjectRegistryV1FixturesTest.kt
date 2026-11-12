package com.letta.mobile.data.projects

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * letta-mobile-m0xhd (S0): the project-registry-v1 fixtures stay valid JSON and stay in step with the
 * contract document. Every field, state, error code and limit a fixture uses must be one the document
 * names, and the rules the document states about records (one primary checkout, what an unconfirmed
 * record may carry, page order) must hold in the fixtures. No host or client code reads these yet;
 * later stages decode them through their real types.
 */
class ProjectRegistryV1FixturesTest {
    private val dir = File(
        ProjectRegistryV1FixturesTest::class.java.getResource(ROOT)?.toURI() ?: error("missing fixture dir $ROOT"),
    )
    private val contract: String = generateSequence(dir.absoluteFile) { it.parentFile }
        .map { File(it, CONTRACT_PATH) }
        .firstOrNull(File::isFile)
        ?.readText()
        ?: error("missing $CONTRACT_PATH")

    private fun fixture(name: String): JsonObject = Json.parseToJsonElement(File(dir, name).readText()).jsonObject

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.cases(): List<JsonObject> = getValue("cases").jsonArray.map { it.jsonObject }

    private fun named(value: String): Boolean = "`$value`" in contract

    /** Every record a fixture shows: the three record files plus each record inside a method result. */
    private fun records(): List<Pair<String, JsonObject>> {
        val files = RECORD_FILES.map { it to fixture(it) }
        val listed = fixture(LIST_FIXTURE).cases().flatMap { case ->
            val items = case["result"]?.jsonObject?.get("items") as? JsonArray ?: return@flatMap emptyList()
            items.map { "list/${case.text("name")}" to it.jsonObject }
        }
        val fetched = fixture(GET_FIXTURE).cases().mapNotNull { case ->
            case["result"]?.jsonObject?.let { "get/${case.text("name")}" to it }
        }
        return files + listed + fetched
    }

    @Test
    fun everyFixtureParsesCitesItsSectionAndIsListedInTheContract() {
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty()
        assertEquals(EXPECTED_FIXTURES, files.map(File::getName).toSet())
        files.forEach { f ->
            assertTrue(fixture(f.name).text("_contract").startsWith("project-registry-v1 §"), "${f.name} cites its section")
            assertTrue(named(f.name), "${f.name} is listed in the contract's fixture table")
        }
    }

    @Test
    fun projectFilesCarryOnlyContractFieldsAndObeyTheirLimits() {
        PROJECT_FILES.forEach { name ->
            val file = fixture(name)
            assertTrue(file.keys.containsAll(FILE_REQUIRED), "$name has the required fields")
            assertTrue((file.keys - "_contract" - FILE_REQUIRED - "engine").isEmpty(), "$name has no other field")
            assertEquals(SCHEMA_VERSION, file.getValue("schemaVersion").jsonPrimitive.int, "$name schema version")
            assertTrue(PROJECT_ID.matches(file.text("projectId")), "$name project id is a lower-case UUID")
            assertTrue(file.text("name").length in 1..MAX_NAME, "$name name length")
            file["engine"]?.jsonObject?.let { assertEngineSettings(name, it) }
            // F1: no path and no agent id anywhere in the file.
            assertFalse(file.toString().contains("agent-") || file.toString().contains("\"path\""), "$name holds no path or agent id")
        }
        assertFalse("engine" in fixture("project-file-minimal.json"), "the minimal file has no engine block")
    }

    private fun assertEngineSettings(label: String, engine: JsonObject) {
        assertTrue((engine.keys - ENGINE_SETTINGS - "enabled").isEmpty(), "$label engine fields")
        engine["formula"]?.let { assertTrue(FORMULA.matches(it.jsonPrimitive.content), "$label formula grammar") }
        engine["readyLabel"]?.let { assertTrue(READY_LABEL.matches(it.jsonPrimitive.content), "$label ready label grammar") }
        engine["poolSize"]?.let { assertTrue(it.jsonPrimitive.int in 1..MAX_POOL, "$label pool size") }
        engine["ciChecks"]?.jsonArray?.let { checks ->
            assertTrue(checks.size <= MAX_CI_CHECKS, "$label ci check count")
            checks.forEach { assertTrue(it.jsonPrimitive.content.length in 1..MAX_CI_CHECK_CHARS, "$label ci check length") }
        }
    }

    @Test
    fun everyRecordCarriesOnlyContractFieldsAndAKnownState() {
        records().forEach { (label, record) ->
            assertTrue(record.keys.containsAll(RECORD_REQUIRED), "$label has the required record fields")
            assertTrue((record.keys - "_contract" - RECORD_REQUIRED - RECORD_OPTIONAL).isEmpty(), "$label has no other field")
            (record.keys - "_contract").forEach { assertTrue(named(it), "$label: field $it is named in the contract") }
            assertTrue(record.text("state") in STATES, "$label state")
            assertTrue(PROJECT_ID.matches(record.text("projectId")), "$label project id")
            assertEquals(record.text("state") == "invalid", "invalidReason" in record, "$label: a reason only when invalid")
            record["invalidReason"]?.let { assertTrue(it.jsonPrimitive.content in INVALID_REASONS, "$label invalid reason") }
            record["repository"]?.jsonObject?.let { assertTrue(SLUG.matches(it.text("slug")), "$label repository slug (R7)") }
            record.getValue("roleAgents").jsonObject.keys.forEach { assertTrue(ROLE.matches(it), "$label role name (R6)") }
            val engine = record.getValue("engine").jsonObject
            assertTrue(engine.keys.containsAll(setOf("enabled", "ciChecks")), "$label engine has enabled and ciChecks")
            assertEngineSettings(label, engine)
        }
        (STATES + INVALID_REASONS).forEach { assertTrue(named(it), "$it is named in the contract") }
    }

    @Test
    fun checkoutsHaveOnePrimaryAndUnrunnableRecordsCarryNoEngineWork() {
        records().forEach { (label, record) ->
            val checkouts = record.getValue("checkouts").jsonArray.map { it.jsonObject }
            checkouts.forEach { assertEquals(CHECKOUT_FIELDS, it.keys, "$label checkout fields") }
            if (checkouts.isNotEmpty()) {
                assertEquals(1, checkouts.count { it.getValue("primary").jsonPrimitive.boolean }, "$label: one primary checkout (R2)")
            }
            checkouts.forEach {
                assertTrue(it.text("path").endsWith("/" + it.text("relativePath")), "$label path ends in its relative path")
            }
            val engine = record.getValue("engine").jsonObject
            val enabled = engine.getValue("enabled").jsonPrimitive.boolean
            if (enabled) assertEquals("active", record.text("state"), "$label: enabled only when active (R5)")
            if (record.text("state") == "unconfirmed") {
                assertFalse("pmAgentId" in record, "$label: no PM when unconfirmed (R4)")
                assertTrue(record.getValue("roleAgents").jsonObject.isEmpty(), "$label: no role agents when unconfirmed (R4)")
                assertEquals(setOf("enabled", "ciChecks"), engine.keys, "$label: no engine settings when unconfirmed (R4)")
                assertFalse(enabled, "$label: not enabled when unconfirmed (R4)")
            }
        }
        val active = fixture("record-active.json")
        assertTrue(active.getValue("checkouts").jsonArray.size > 1, "the active record shows more than one checkout")
    }

    @Test
    fun methodFixturesNameTheirMethodCapabilityAndOneOutcomePerCase() {
        mapOf(LIST_FIXTURE to "project.registry.list", GET_FIXTURE to "project.registry.get").forEach { (file, method) ->
            val fixture = fixture(file)
            assertEquals(method, fixture.text("method"))
            assertEquals(CAPABILITY, fixture.text("capability"))
            assertTrue(named(method) && named(CAPABILITY), "$method and its capability are named in the contract")
            val cases = fixture.cases()
            assertEquals(cases.size, cases.map { it.text("name") }.toSet().size, "$file case names are unique")
            cases.forEach { case ->
                val label = "$file/${case.text("name")}"
                assertTrue(("result" in case) != ("error" in case), "$label has exactly one outcome")
                case.getValue("params").jsonObject.keys.forEach { assertTrue(named(it), "$label: param $it is named in the contract") }
                case["error"]?.jsonObject?.let { assertRefusal(label, it) }
            }
        }
    }

    private fun assertRefusal(label: String, error: JsonObject) {
        assertFalse(error.getValue("success").jsonPrimitive.boolean, "$label refusal envelope")
        val code = error.text("error")
        assertTrue(code in ERROR_CODES && named(code), "$label error code $code")
        assertEquals(code == "invalid_request", "problem" in error, "$label: a problem only for invalid_request")
        error["problem"]?.let { assertTrue(it.jsonPrimitive.content in PROBLEMS && named(it.jsonPrimitive.content), "$label problem") }
    }

    /** One answered list case: its name, its params and its result. */
    private class ListAnswer(val label: String, val params: JsonObject, val result: JsonObject) {
        val items: List<JsonObject>? = (result["items"] as? JsonArray)?.map { it.jsonObject }
        val notModified: Boolean = result["notModified"]?.jsonPrimitive?.boolean == true
    }

    private fun listAnswers(): List<ListAnswer> = fixture(LIST_FIXTURE).cases().mapNotNull { case ->
        case["result"]?.jsonObject?.let { ListAnswer(case.text("name"), case.getValue("params").jsonObject, it) }
    }

    @Test
    fun everyListAnswerNamesItsRevisionAndCarriesOnlyContractFields() {
        val answers = listAnswers()
        answers.forEach { answer ->
            assertTrue((answer.result.keys - LIST_RESULT_FIELDS).isEmpty(), "${answer.label} result fields")
            assertTrue("revision" in answer.result, "${answer.label} names the revision it was read at")
        }
        assertTrue(answers.any { "nextCursor" in it.result }, "a case shows a further page")
        assertEquals(ERROR_CODES + PROBLEMS, errorsShown(), "every error code and problem has a fixture case")
    }

    @Test
    fun aNotModifiedAnswerCarriesNoItemsAndRepeatsTheHeldRevision() {
        val unchanged = listAnswers().filter(ListAnswer::notModified)
        assertTrue(unchanged.isNotEmpty(), "a case shows notModified")
        unchanged.forEach { answer ->
            assertEquals(null, answer.items, "${answer.label}: notModified carries no items")
            assertEquals(answer.params.text("ifRevision"), answer.result.text("revision"), "${answer.label}: the held revision")
        }
    }

    @Test
    fun listPagesAreBoundedOrderedAndHonourTheEngineFilter() {
        listAnswers().filterNot(ListAnswer::notModified).forEach { answer ->
            val items = checkNotNull(answer.items) { "${answer.label} has items" }
            val ids = items.map { it.text("projectId") }
            val limit = answer.params["limit"]?.jsonPrimitive?.int ?: DEFAULT_LIMIT
            assertTrue(ids.size <= limit, "${answer.label}: at most limit records")
            assertEquals(ids.sorted(), ids, "${answer.label}: ordered by projectId (L1)")
            val wanted = answer.params["engineEnabled"]?.jsonPrimitive?.boolean ?: return@forEach
            assertTrue(items.all { it.engineEnabled() == wanted }, "${answer.label}: only records with engine.enabled = $wanted")
        }
    }

    private fun JsonObject.engineEnabled(): Boolean = getValue("engine").jsonObject.getValue("enabled").jsonPrimitive.boolean

    /** The error codes and problems the method fixtures exercise between them. */
    private fun errorsShown(): Set<String> = (fixture(LIST_FIXTURE).cases() + fixture(GET_FIXTURE).cases())
        .mapNotNull { it["error"]?.jsonObject }
        .flatMap { listOfNotNull(it.text("error"), it["problem"]?.jsonPrimitive?.content) }
        .toSet()

    @Test
    fun theLimitsTheTestEnforcesAreTheOnesTheContractStates() {
        listOf(
            "1 to $MAX_NAME characters", "1 to $MAX_POOL", "at most $MAX_CI_CHECKS entries",
            "1 to $MAX_CI_CHECK_CHARS characters", "1 to $MAX_LIMIT; default $DEFAULT_LIMIT", "at most 16 KiB",
            "once every 15 seconds", "`.meridian/project.json`",
        ).forEach { assertTrue(it in contract, "the contract states: $it") }
    }

    private companion object {
        const val ROOT = "/project-registry-v1"
        const val CONTRACT_PATH = "docs/reference/contracts/project-registry-v1.md"
        const val LIST_FIXTURE = "project.registry.list.json"
        const val GET_FIXTURE = "project.registry.get.json"
        const val CAPABILITY = "projects.read"
        const val SCHEMA_VERSION = 1
        const val MAX_NAME = 120
        const val MAX_POOL = 8
        const val MAX_CI_CHECKS = 16
        const val MAX_CI_CHECK_CHARS = 100
        const val MAX_LIMIT = 100
        const val DEFAULT_LIMIT = 50
        val PROJECT_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val FORMULA = Regex("[a-z0-9][a-z0-9-]{0,63}")
        val READY_LABEL = Regex("[a-z0-9][a-z0-9:_-]{0,63}")
        val SLUG = Regex("[A-Za-z0-9._-]+/[A-Za-z0-9._-]+")
        val ROLE = Regex("[a-z][a-z0-9-]*")
        val PROJECT_FILES = listOf("project-file.json", "project-file-minimal.json")
        val RECORD_FILES = listOf("record-active.json", "record-unconfirmed.json", "record-invalid.json")
        val EXPECTED_FIXTURES = (PROJECT_FILES + RECORD_FILES + LIST_FIXTURE + GET_FIXTURE).toSet()
        val FILE_REQUIRED = setOf("schemaVersion", "projectId", "name")
        val ENGINE_SETTINGS = setOf("formula", "readyLabel", "poolSize", "ciChecks")
        val RECORD_REQUIRED = setOf("projectId", "name", "state", "checkouts", "roleAgents", "engine", "revision")
        val RECORD_OPTIONAL = setOf("invalidReason", "repository", "pmAgentId", "providerKind")
        val CHECKOUT_FIELDS = setOf("root", "relativePath", "path", "primary")
        val LIST_RESULT_FIELDS = setOf("revision", "notModified", "items", "nextCursor")
        val STATES = setOf("active", "unconfirmed", "archived", "invalid")
        val INVALID_REASONS = setOf(
            "file_too_large", "not_json", "duplicate_key", "missing_field", "field_out_of_range", "unsupported_schema_version",
        )
        val ERROR_CODES = setOf("invalid_request", "cursor_stale", "unknown_project")
        val PROBLEMS = setOf("limit_out_of_range", "cursor_malformed", "if_revision_with_cursor", "project_id_malformed")
    }
}
