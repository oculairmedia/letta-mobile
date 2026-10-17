// letta-mobile-by4vm.121: make a cancelled local-backend turn abort its provider request.
// letta.js 0.34.14's local backend never wires a cancel into the LLM call: createLocalExecutor()
// builds its PiStreamAdapter without an abortSignal, and the AbortController that
// createProviderLettaStream() returns (the one the backend's cancelRun/cancelConversation abort on
// `abort_message`) is attached to nothing. So a Stop ended the turn on the wire while the provider
// fetch ran on (until the endpoint answered or Node's 300 s header timeout fired), the App Server
// kept the conversation's lease for that long, and every later input queued behind it. Against
// the on-device LiteRT bridge that meant the phone's single engine kept generating for nobody.
// This patch gives each provider turn its own signal, aborted with the stream's controller, and
// makes the adapter prefer that per-turn signal over its (unset) constructor one.
import { readFileSync, writeFileSync } from 'fs';
import { patchTarget } from './patch-target.mjs';
const file = patchTarget();
let src = readFileSync(file, 'utf8');

function replaceOnce(token, replacement, what) {
  const count = src.split(token).length - 1;
  if (count !== 1) {
    throw new Error(`patch-provider-abort: expected 1 ${what} token, found ${count} — letta.js shape changed`);
  }
  src = src.replace(token, replacement);
}

replaceOnce(
  [
    '  async execute(input) {',
    '    const providerInput = buildProviderTurnInput(input);',
    '    const events = await this.adapter.stream(providerInput);',
    '    return createProviderLettaStream(events, estimateProviderContextTokens(providerInput));',
    '  }',
  ].join('\n'),
  [
    '  async execute(input) {',
    '    const providerInput = buildProviderTurnInput(input);',
    '    const lettaMobileAbort = new AbortController;',
    '    providerInput.abortSignal = lettaMobileAbort.signal;',
    '    const events = await this.adapter.stream(providerInput);',
    '    const lettaMobileStream = createProviderLettaStream(events, estimateProviderContextTokens(providerInput));',
    '    lettaMobileStream.controller.signal.addEventListener("abort", () => lettaMobileAbort.abort(), { once: true });',
    '    return lettaMobileStream;',
    '  }',
  ].join('\n'),
  'ProviderTurnExecutor.execute',
);

// Every read of the adapter's signal (7, in 4 places) sits in a method whose turn argument is named `input`
// (compactBeforeProviderCall, streamOnce, the stream() retry loop); the assignment stays.
const assignment = 'this.abortSignal = options.abortSignal;';
const reads = src.split('this.abortSignal').length - 1;
if (reads !== 8 || src.split(assignment).length - 1 !== 1) {
  throw new Error(`patch-provider-abort: expected 8 this.abortSignal tokens (1 assignment), found ${reads} — letta.js shape changed`);
}
const placeholder = '__LETTA_MOBILE_ABORT_ASSIGNMENT__';
src = src
  .replace(assignment, placeholder)
  .split('this.abortSignal')
  .join('(input.abortSignal ?? this.abortSignal)')
  .replace(placeholder, assignment);

writeFileSync(file, src);
console.log('[embedded-lettacode] patched local provider turns to abort their request on cancel');
