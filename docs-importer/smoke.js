'use strict';

// Minimal smoke test for the Teedy importer.
//
// main.js is a CLI that starts an interactive wizard on load, so it cannot be
// required directly without hanging. Instead this test verifies:
//   1. main.js parses (see the `node --check` step in the npm "test" script).
//   2. Every production dependency still resolves after the request removal.
//   3. The built-in HTTP primitives the importer now relies on are present,
//      guarding against running on a Node version too old for fetch /
//      fs.openAsBlob() (the streaming multipart upload).
//   4. The deprecated `request` package is gone.

const fs = require('fs');
const assert = require('assert');

// 2. Dependencies declared in package.json all load.
for (const dep of [
  'inquirer',
  'minimist',
  'ora',
  'preferences',
  'recursive-readdir',
  'minimatch',
  'underscore',
]) {
  require(dep);
}

// 3. Built-in primitives used by the modern HTTP layer.
assert.strictEqual(typeof fetch, 'function', 'global fetch missing (Node >=18 required)');
assert.strictEqual(typeof FormData, 'function', 'global FormData missing');
assert.strictEqual(typeof File, 'function', 'global File missing (Node >=20 required)');
assert.strictEqual(typeof fs.openAsBlob, 'function', 'fs.openAsBlob missing (Node >=20 required)');

// 4. The deprecated request library must not be reachable.
let requestResolvable = false;
try {
  require.resolve('request');
  requestResolvable = true;
} catch (e) {
  // expected
}
assert.strictEqual(requestResolvable, false, 'deprecated `request` package is still installed');

console.log('docs-importer smoke test OK');
