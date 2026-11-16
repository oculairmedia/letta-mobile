package com.letta.mobile.data.files

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

internal fun readUtf8(path: Path): String = UTF_8.newDecoder().decode(ByteBuffer.wrap(Files.readAllBytes(path))).toString()

internal fun writeUtf8(path: Path, text: String) {
    Files.write(path, text.toByteArray(UTF_8))
}
