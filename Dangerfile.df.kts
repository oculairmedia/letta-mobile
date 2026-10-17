// Danger Kotlin PR-hygiene pilot (letta-mobile-fi7th). ADVISORY: never a required check.
// Complements, and deliberately does not duplicate, the rules already enforced by
// scripts/ci/agents-policy-check.sh (raw hex colors, sharedLogic JVM APIs, AlertDialog) and
// scripts/ci/platform-ui-gate.sh (PUI001-PUI004, the authoritative new-platform-UI-file check).
// Run locally:  danger-kotlin pr https://github.com/oculairmedia/letta-mobile/pull/<n>
import systems.danger.kotlin.*
import java.io.File

val LARGE_PR_LINES = 1500

val CODE_ROOTS = listOf(
    "android-compose/web/", "android-compose/desktop/", "android-compose/app/",
    "android-compose/sharedUI/", "android-compose/sharedLogic/",
)
val PLATFORM_UI_ROOTS = listOf(
    "android-compose/desktop/src/main/", "android-compose/app/src/main/",
    "android-compose/designsystem/src/main/", "android-compose/web/src/wasmJsMain/",
)
val SRC_MARKERS = listOf("/src/main/", "/src/commonMain/", "/src/jvmMain/", "/src/androidMain/", "/src/wasmJsMain/")
val TEST_MARKERS = listOf("/src/test/", "/src/commonTest/", "/src/jvmTest/", "/src/androidTest/", "/src/androidUnitTest/")

// Baselines/allowlists that may only shrink (detekt-baseline.xml files are matched by suffix).
val RATCHETS = listOf(
    "scripts/ci/platform-ui-allowlist.txt",
    "android-compose/architecture-tests/json-instances-baseline.txt",
    "android-compose/architecture-tests/raw-id-params-baseline.txt",
)
val DETEKT_BASELINE_SUFFIX = "detekt-baseline.xml"

// PR-body reuse questions: label to a regex that shows the author answered it.
val REUSE_QUESTIONS = listOf(
    "What shared code did you reuse?" to Regex("(?i)reus"),
    "What did you add to common (sharedUI/sharedLogic)?" to Regex("(?i)(added to|common)"),
    "If platform-only, why?" to Regex("(?i)platform[- ]only"),
    "Lines added/removed per platform vs common?" to Regex("(?i)(lines|\\+\\d+\\s*/\\s*-\\d+|loc)"),
)

class Ctx(
    val changed: List<String>,
    val created: List<String>,
    val body: String,
    val baseSha: String,
)

danger(args) {
    val ctx = Ctx(
        changed = git.createdFiles + git.modifiedFiles,
        created = git.createdFiles,
        body = github.pullRequest.body ?: "",
        baseSha = github.pullRequest.base.sha,
    )

    checkReuseChecklist(ctx)
    checkPlatformUiReason(ctx)
    checkBeadReference(ctx, git.commits.map { it.message ?: "" })
    checkNoBom(ctx)
    checkCoAuthoredBy(git.commits)
    checkRatchets(ctx)
    checkSize(github.pullRequest.additions + github.pullRequest.deletions)
    checkTestsAccompanySource(ctx)
    checkPlanningDocs(ctx)
}

fun checkReuseChecklist(ctx: Ctx) {
    if (ctx.changed.none { f -> CODE_ROOTS.any { f.startsWith(it) } }) return
    val missing = REUSE_QUESTIONS.filter { (_, re) -> !re.containsMatchIn(ctx.body) }.map { it.first }
    if (missing.isEmpty()) return
    warn(
        "PR touches web/desktop/app/sharedUI/sharedLogic but the body does not answer the reuse " +
            "questions: ${missing.joinToString("; ")}. Add a short 'Reuse' section.",
    )
}

// scripts/ci/platform-ui-gate.sh (PUI001/PUI004) is authoritative; this is an early hint.
fun checkPlatformUiReason(ctx: Ctx) {
    val newPlatformUi = ctx.created.filter { f -> f.endsWith(".kt") && PLATFORM_UI_ROOTS.any { f.startsWith(it) } }
    if (newPlatformUi.isEmpty()) return
    if (Regex("(?m)^\\s*Platform-UI-Reason:\\s*\\S+").containsMatchIn(ctx.body)) return
    warn(
        "New file(s) under platform UI paths (${newPlatformUi.size}, e.g. ${newPlatformUi.first()}) with no " +
            "'Platform-UI-Reason: <why>' line in the PR body. UI belongs in sharedUI; see the Platform UI gate (PUI001/PUI004).",
    )
}

fun checkBeadReference(ctx: Ctx, commitMessages: List<String>) {
    val bead = Regex("letta-mobile-[a-z0-9]{3,}(\\.\\d+)*")
    if (bead.containsMatchIn(ctx.body) || commitMessages.any { bead.containsMatchIn(it) }) return
    warn("No bead id (letta-mobile-xxxxx) in the PR body or any commit message.")
}

fun checkNoBom(ctx: Ctx) {
    val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    val offenders = ctx.changed.filter { it.endsWith(".kt") || it.endsWith(".kts") }.filter { path ->
        val f = File(path)
        f.isFile && f.inputStream().use { s -> ByteArray(3).also { s.read(it) }.contentEquals(bom) }
    }
    if (offenders.isNotEmpty()) fail("UTF-8 BOM in changed Kotlin files (strip it): ${offenders.joinToString()}")
}

fun checkCoAuthoredBy(commits: List<GitCommit>) {
    val missing = commits.filter { c ->
        val msg = c.message ?: ""
        !msg.startsWith("Merge ") &&
            msg.lineSequence().none { it.trim().startsWith("Co-Authored-By:", ignoreCase = true) }
    }
    if (missing.isNotEmpty()) {
        warn("${missing.size} commit(s) lack a Co-Authored-By trailer: ${missing.joinToString { (it.sha ?: "?").take(8) }}")
    }
}

// Number of real entries: non-blank, non-comment lines (detekt: <ID> elements).
fun entryCount(path: String, text: String): Int =
    if (path.endsWith(".xml")) Regex("<ID>").findAll(text).count()
    else text.lines().count { it.isNotBlank() && !it.trimStart().startsWith("#") }

fun baseText(baseSha: String, path: String): String? {
    val p = ProcessBuilder("git", "show", "$baseSha:$path").start()
    val out = p.inputStream.bufferedReader().readText()
    return if (p.waitFor() == 0) out else null
}

fun checkRatchets(ctx: Ctx) {
    val candidates = ctx.changed.filter { f -> f in RATCHETS || f.endsWith(DETEKT_BASELINE_SUFFIX) }
    for (path in candidates) {
        val head = File(path).takeIf { it.isFile }?.readText() ?: continue
        val base = baseText(ctx.baseSha, path) ?: continue // brand-new baseline files are a reviewer call
        val before = entryCount(path, base)
        val after = entryCount(path, head)
        if (after > before) {
            fail("Baseline/allowlist must only shrink: $path grew $before -> $after entries. Fix the violation instead of baselining it.")
        }
    }
}

fun checkSize(changedLines: Int) {
    if (changedLines > LARGE_PR_LINES) {
        warn("Large PR: $changedLines changed lines (> $LARGE_PR_LINES). Consider splitting into slices.")
    }
}

fun checkTestsAccompanySource(ctx: Ctx) {
    val srcChanged = ctx.changed.any { f -> f.endsWith(".kt") && SRC_MARKERS.any { f.contains(it) } }
    val testChanged = ctx.changed.any { f -> TEST_MARKERS.any { f.contains(it) } }
    if (srcChanged && !testChanged) warn("Source changed under src/main-style sets but no test files changed.")
}

fun checkPlanningDocs(ctx: Ctx) {
    val planning = Regex("(?i)(plan|design|proposal|rfc|roadmap|spec)")
    val added = ctx.created.filter {
        it.startsWith("docs/") && it.endsWith(".md") && planning.containsMatchIn(it.substringAfterLast('/'))
    }
    if (added.isNotEmpty() && ctx.changed.all { it.startsWith("docs/") }) {
        fail("Docs-only PR adds planning/design docs (${added.joinToString()}). Plans go in bead design/notes fields, not docs/.")
    }
}
