// kotlin-web-helpers 2.4.10 writes adjacent TeamCity service messages without
// line separators. Kotlin Gradle's fixed-size parser then overflows on larger
// suites and terminates the browser process. Preserve every message while
// making the reporter's record boundary explicit.
const originalStdoutWrite = process.stdout.write.bind(process.stdout)

process.stdout.write = function (chunk, encoding, callback) {
  const text = String(chunk)
  const terminatedChunk = text.startsWith('##teamcity[') && !text.endsWith('\n')
    ? `${text}\n`
    : chunk
  return originalStdoutWrite(terminatedChunk, encoding, callback)
}
