package com.letta.mobile.docs

import java.io.File

/** Replaces only a reference's marked body; the caller supplies its authoritative renderer. */
internal object GeneratedReferenceDocs {
    const val MODE_PROPERTY = "letta.contractReferenceDocs.mode"

    enum class Mode { Check, Write }

    data class Markers(val start: String, val end: String)

    fun sync(
        document: File,
        markers: Markers,
        generated: String,
        mode: Mode = configuredMode(),
    ) {
        check(document.isFile) { "Missing reference: ${document.absolutePath}" }
        val original = document.readText(Charsets.UTF_8)
        val updated = replaceBody(original, markers, generated)
        when (mode) {
            Mode.Check -> check(original == updated) {
                "${document.path} is stale; run ./gradlew :sharedLogic:writeContractReferenceDocs from android-compose/."
            }
            Mode.Write -> if (original != updated) document.writeText(updated, Charsets.UTF_8)
        }
    }

    internal fun configuredMode(value: String? = System.getProperty(MODE_PROPERTY)): Mode = when (value) {
        null, "check" -> Mode.Check
        "write" -> Mode.Write
        else -> error("Unsupported $MODE_PROPERTY=$value; expected check or write")
    }

    private fun replaceBody(original: String, markers: Markers, generated: String): String {
        val (startMarker, endMarker) = markers
        require(startMarker.isNotEmpty() && endMarker.isNotEmpty() && startMarker != endMarker) {
            "Reference markers must be nonempty and distinct"
        }
        val start = uniqueMarker(original, startMarker)
        val end = uniqueMarker(original, endMarker)
        val bodyStart = start + startMarker.length
        require(end > bodyStart) { "Reference markers are reversed or have no line between them" }
        require(start == 0 || original[start - 1] == '\n') { "Start marker must occupy its own line" }
        require(original[end - 1] == '\n') { "End marker must occupy its own line" }
        require(end + endMarker.length == original.length || original[end + endMarker.length] in "\r\n") {
            "End marker must occupy its own line"
        }
        val newline = if (original.startsWith("\r\n", bodyStart)) "\r\n" else "\n"
        require(original.startsWith(newline, bodyStart)) { "Start marker must occupy its own line" }
        val body = generated.replace("\r\n", "\n").trim('\n')
        require(startMarker !in body && endMarker !in body) { "Generated text contains reference markers" }
        return original.substring(0, bodyStart) + newline + body.replace("\n", newline) + newline + original.substring(end)
    }

    private fun uniqueMarker(text: String, marker: String): Int {
        val index = text.indexOf(marker)
        require(index >= 0 && text.indexOf(marker, index + marker.length) < 0) {
            "Expected exactly one reference marker: $marker"
        }
        return index
    }
}
