// kotlin-web-helpers 2.4.10 writes adjacent TeamCity service messages without
// line separators. Kotlin Gradle's fixed-size parser then overflows on larger
// suites and terminates the browser process. Buffer arbitrary stdout chunks and
// emit each complete service message as one uninterrupted line.
const teamCityPrefix = '##teamcity['
const originalStdoutWrite = process.stdout.write.bind(process.stdout)
let pendingStdout = ''

function partialPrefixLength(text) {
  const limit = Math.min(text.length, teamCityPrefix.length - 1)
  for (let length = limit; length > 0; length -= 1) {
    if (text.endsWith(teamCityPrefix.slice(0, length))) return length
  }
  return 0
}

function messageEnd(text) {
  let escaped = false
  for (let index = teamCityPrefix.length; index < text.length; index += 1) {
    const character = text[index]
    if (escaped) {
      escaped = false
    } else if (character === '|') {
      escaped = true
    } else if (character === ']') {
      return index
    }
  }
  return -1
}

function framedWrites(chunk) {
  pendingStdout += String(chunk)
  const writes = []
  while (pendingStdout.length > 0) {
    const start = pendingStdout.indexOf(teamCityPrefix)
    if (start < 0) {
      const retained = partialPrefixLength(pendingStdout)
      const readyLength = pendingStdout.length - retained
      if (readyLength > 0) writes.push(pendingStdout.slice(0, readyLength))
      pendingStdout = pendingStdout.slice(readyLength)
      break
    }
    if (start > 0) {
      writes.push(pendingStdout.slice(0, start))
      pendingStdout = pendingStdout.slice(start)
    }
    const end = messageEnd(pendingStdout)
    if (end < 0) break
    writes.push(`${pendingStdout.slice(0, end + 1)}\n`)
    pendingStdout = pendingStdout.slice(end + 1)
  }
  return writes
}

process.stdout.write = function (chunk, encoding, callback) {
  const completion = typeof encoding === 'function' ? encoding : callback
  const outputEncoding = typeof encoding === 'function' ? undefined : encoding
  const writes = framedWrites(chunk)
  let acceptsMoreData = true
  writes.forEach((write, index) => {
    const accepted = originalStdoutWrite(
      write,
      outputEncoding,
      index === writes.length - 1 ? completion : undefined,
    )
    if (accepted === false) acceptsMoreData = false
  })
  if (writes.length === 0 && typeof completion === 'function') queueMicrotask(completion)
  return acceptsMoreData
}

process.on('beforeExit', () => {
  if (pendingStdout.length > 0) {
    originalStdoutWrite(pendingStdout)
    pendingStdout = ''
  }
})
