import re

with open("android-compose/core/data/src/test/java/com/letta/mobile/data/timeline/TimelineSyncLoopTest.kt", "r") as f:
    content = f.read()

# Add org.junit.Rule imports if missing
if "import org.junit.Rule" not in content:
    content = content.replace("import org.junit.Test", "import org.junit.Rule\nimport org.junit.rules.ExternalResource\nimport org.junit.Test")

# We create the TrackingRule
rule_definition = """class TrackingRule : ExternalResource() {
    val loops = mutableListOf<TimelineSyncLoop>()
    val scopes = mutableListOf<CoroutineScope>()

    fun trackLoop(loop: TimelineSyncLoop): TimelineSyncLoop {
        loops.add(loop)
        return loop
    }

    fun trackScope(scope: CoroutineScope): CoroutineScope {
        scopes.add(scope)
        return scope
    }

    override fun after() {
        loops.forEach { it.close() }
        loops.clear()
        scopes.forEach { it.cancel() }
        scopes.clear()
    }
}
"""

if "class TrackingRule" not in content:
    content = content.replace("@Tag(\"integration\")", rule_definition + "\n@Tag(\"integration\")")

# In TimelineSyncLoopTest, replace the `loops`/`scopes` fields with the Rule
test_class_replace = """    @get:Rule
    val tracker = TrackingRule()"""

content = re.sub(r'    private val loops = mutableListOf<TimelineSyncLoop>\(\)\n    private val scopes = mutableListOf<CoroutineScope>\(\)\n\n    @After\n    fun tearDown\(\) \{\n        loops\.forEach \{ it\.close\(\) \}\n        loops\.clear\(\)\n        scopes\.forEach \{ it\.cancel\(\) \}\n        scopes\.clear\(\)\n    \}', test_class_replace, content)

# Also in TimelineSyncLoopImageRestoreTest
content = re.sub(r'    private val loops = mutableListOf<TimelineSyncLoop>\(\)\n    private val scopes = mutableListOf<CoroutineScope>\(\)\n\n    @After\n    fun tearDown\(\) \{\n        loops\.forEach \{ it\.close\(\) \}\n        loops\.clear\(\)\n        scopes\.forEach \{ it\.cancel\(\) \}\n        scopes\.clear\(\)\n    \}', test_class_replace, content)

# Now we must be very careful with `.also { loops.add(it) }` because `loops` is no longer a top-level field! It's `tracker.loops`
content = content.replace(".also { loops.add(it) }", ".also { tracker.loops.add(it) }")
content = content.replace(".also { scopes.add(it) }", ".also { tracker.scopes.add(it) }")

with open("android-compose/core/data/src/test/java/com/letta/mobile/data/timeline/TimelineSyncLoopTest.kt", "w") as f:
    f.write(content)
