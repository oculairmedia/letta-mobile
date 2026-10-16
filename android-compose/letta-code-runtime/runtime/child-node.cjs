// child-node.cjs — preload (`node --require`) that makes letta-code's "run another node" launches
// work when node runs inside the Android app (letta-mobile-by4vm.121.12).
//
// The on-device App Server runs on the in-process libnode, so process.execPath is the app's own
// executable, /system/bin/app_process64 (ART). letta-code starts helpers with
// spawn(process.execPath, [script, …]) — the image worker (image-resize-worker.js: sharp, every
// image sent to a model goes through it) and the memory validator — and ART started with node
// arguments aborts: image sends failed with "Failed to prepare image for model: Image worker
// exited from signal SIGABRT". It relaunches ITSELF (subagents, the `letta` shell shim) by
// exec'ing letta.js directly, which an app cannot do (app data is not executable; no
// /usr/bin/env).
//
// When LETTA_CHILD_NODE_PATH names a real node (the Android launcher points it at the packaged
// node CLI, libnodecli.so; desktop and CI node never need this and leave it unset):
//  - process.execPath becomes that node;
//  - every child started on it gets this process's `--require` preloads in front of its
//    arguments, the way fork() passes execArgv. The child is the same Node 18 without ICU and
//    needs the same shims; spawn() has no execArgv, and NODE_OPTIONS is not used for them;
//  - LETTA_CODE_BIN / LETTA_CODE_BIN_ARGS_JSON (letta-code's own launcher override) run letta.js
//    on that node with the same preloads.
// Shared by the App Server process and the children it starts (they load it again through the
// forwarded preloads), so it is idempotent: arguments that already start with the preloads are
// left alone, and a set LETTA_CODE_BIN is kept.
'use strict';
const childNode = process.env.LETTA_CHILD_NODE_PATH;
if (childNode) {
  const path = require('node:path');
  const childProcess = require('node:child_process');
  const { promisify } = require('node:util');

  const preloads = [];
  const execArgv = process.execArgv;
  for (let i = 0; i < execArgv.length; i++) {
    const arg = execArgv[i];
    if ((arg === '--require' || arg === '-r') && i + 1 < execArgv.length) preloads.push('--require', execArgv[++i]);
    else if (arg.startsWith('--require=')) preloads.push('--require', arg.slice('--require='.length));
  }

  Object.defineProperty(process, 'execPath', { value: childNode, writable: true, configurable: true, enumerable: true });

  const startsWithPreloads = (args) => preloads.every((arg, i) => args[i] === arg);
  // (file, args?, options?, callback?) with the preloads in front of args; args may be omitted.
  const onChildNode = (file, rest) => {
    if (file !== childNode || preloads.length === 0) return rest;
    if (rest.length === 0 || rest[0] == null) return [preloads.slice(), ...rest.slice(1)];
    if (!Array.isArray(rest[0])) return [preloads.slice(), ...rest];
    return startsWithPreloads(rest[0]) ? rest : [[...preloads, ...rest[0]], ...rest.slice(1)];
  };
  for (const name of ['spawn', 'spawnSync', 'execFile', 'execFileSync']) {
    const original = childProcess[name];
    const wrapped = function (file, ...rest) { return original.call(this, file, ...onChildNode(file, rest)); };
    const custom = original[promisify.custom];
    if (custom) {
      Object.defineProperty(wrapped, promisify.custom, {
        value: (file, ...rest) => custom(file, ...onChildNode(file, rest)),
      });
    }
    childProcess[name] = wrapped;
  }
  require('node:module').syncBuiltinESMExports();

  const entry = process.argv[1];
  if (!process.env.LETTA_CODE_BIN && entry && path.basename(entry) === 'letta.js') {
    process.env.LETTA_CODE_BIN = childNode;
    process.env.LETTA_CODE_BIN_ARGS_JSON = JSON.stringify([...preloads, entry]);
  }
}
