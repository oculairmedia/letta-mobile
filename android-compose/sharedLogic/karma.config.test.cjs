const assert = require('node:assert/strict')
const path = require('node:path')

const originalWrite = process.stdout.write
const writes = []
process.stdout.write = (chunk) => {
  writes.push(String(chunk))
  return true
}

require(path.join(__dirname, 'karma.config.d', 'teamcity-newlines.js'))

process.stdout.write('##teamcity[first]##teamcity[second]')
assert.deepEqual(writes.splice(0), ['##teamcity[first]\n', '##teamcity[second]\n'])

process.stdout.write('##team')
assert.deepEqual(writes.splice(0), [])
process.stdout.write("city[test name='split']")
assert.deepEqual(writes.splice(0), ["##teamcity[test name='split']\n"])

process.stdout.write("prefix ##teamcity[test name='escaped |] bracket'] suffix")
assert.deepEqual(writes.splice(0), [
  'prefix ',
  "##teamcity[test name='escaped |] bracket']\n",
  ' suffix',
])

process.stdout.write = originalWrite
console.log('TeamCity stdout framing tests passed')
