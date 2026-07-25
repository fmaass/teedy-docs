'use strict';

// Functional coverage for every glob path the importer depends on.
//
// main.js reaches minimatch two ways, and both had to keep working when the
// brace-expansion CVE forced the minimatch line to move (issue #198):
//   1. directly, via `minimatch.filter(fileFilter, { matchBase: true })`
//      (main.js:4, :427) — the user-supplied `{pdf,docx}`-style file filter;
//   2. indirectly, through recursive-readdir (main.js:3, :203, :425), which
//      builds its own `new minimatch.Minimatch(pattern, { matchBase: true })`
//      for every ignore entry it is given.
//
// The expectations below were captured against the pre-bump tree (minimatch
// 9.0.9 / brace-expansion 1.1.16 + 2.1.2) so a behaviour change — not merely a
// version change — fails the suite.

const test = require('node:test');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const minimatch = require('minimatch');
const recursive = require('recursive-readdir');

// The importer's own filter call site, reproduced verbatim from main.js:427.
const importerFilter = (pattern) =>
  minimatch.filter(pattern || '*', { matchBase: true });

const SAMPLE = [
  '/in/a.pdf',
  '/in/b.docx',
  '/in/c.txt',
  '/in/sub/d.pdf',
  '/in/sub/deep/e.docx',
  '/in/sub/f.png',
  '/in/.hidden.pdf',
];

test('brace-expanded file filter keeps matching nested files by basename', () => {
  assert.deepStrictEqual(SAMPLE.filter(importerFilter('*.{pdf,docx}')), [
    '/in/a.pdf',
    '/in/b.docx',
    '/in/sub/d.pdf',
    '/in/sub/deep/e.docx',
  ]);
});

test('a wide brace list expands fully (the DoS mitigation must not clip it)', () => {
  const pattern = '*.{pdf,docx,PDF,DOCX,jpg,png,tif,tiff}';
  assert.deepStrictEqual(SAMPLE.filter(importerFilter(pattern)), [
    '/in/a.pdf',
    '/in/b.docx',
    '/in/sub/d.pdf',
    '/in/sub/deep/e.docx',
    '/in/sub/f.png',
  ]);
});

test('nested and ranged braces still expand', () => {
  const files = ['/in/scan-a1.pdf', '/in/scan-b2.pdf', '/in/scan-c3.pdf'];
  assert.deepStrictEqual(
    files.filter(importerFilter('scan-{a,b}{1,2}.pdf')),
    ['/in/scan-a1.pdf', '/in/scan-b2.pdf']
  );
  assert.deepStrictEqual(files.filter(importerFilter('scan-?{1..2}.pdf')), [
    '/in/scan-a1.pdf',
    '/in/scan-b2.pdf',
  ]);
});

test("the default '*' filter passes every non-dot file at any depth", () => {
  assert.deepStrictEqual(SAMPLE.filter(importerFilter(undefined)), [
    '/in/a.pdf',
    '/in/b.docx',
    '/in/c.txt',
    '/in/sub/d.pdf',
    '/in/sub/deep/e.docx',
    '/in/sub/f.png',
  ]);
});

test('dotfiles stay excluded unless the pattern opts in', () => {
  assert.deepStrictEqual(SAMPLE.filter(importerFilter('*.pdf')), [
    '/in/a.pdf',
    '/in/sub/d.pdf',
  ]);
  assert.deepStrictEqual(SAMPLE.filter(importerFilter('.*.pdf')), [
    '/in/.hidden.pdf',
  ]);
});

// --- recursive-readdir -------------------------------------------------------
// main.js calls recursive() without ignores, but the library's ignore path is
// the one that instantiates minimatch, so it gets its own assertions: an
// override that broke it would be invisible to the call sites above.

const withTree = async (body) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'teedy-importer-glob-'));
  try {
    fs.mkdirSync(path.join(root, 'sub', 'deep'), { recursive: true });
    fs.writeFileSync(path.join(root, 'a.pdf'), 'a');
    fs.writeFileSync(path.join(root, 'b.docx'), 'b');
    fs.writeFileSync(path.join(root, 'c.txt'), 'c');
    fs.writeFileSync(path.join(root, 'sub', 'd.pdf'), 'd');
    fs.writeFileSync(path.join(root, 'sub', 'f.png'), 'f');
    fs.writeFileSync(path.join(root, 'sub', 'deep', 'e.docx'), 'e');
    return await body(root);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
};

const relative = (root, files) =>
  files.map((file) => path.relative(root, file).split(path.sep).join('/')).sort();

test('recursive-readdir without ignores walks the whole tree (main.js:203, :425)', () =>
  withTree(async (root) => {
    assert.deepStrictEqual(relative(root, await recursive(root)), [
      'a.pdf',
      'b.docx',
      'c.txt',
      'sub/d.pdf',
      'sub/deep/e.docx',
      'sub/f.png',
    ]);
  }));

test('recursive-readdir applies brace-expanded ignore patterns', () =>
  withTree(async (root) => {
    assert.deepStrictEqual(
      relative(root, await recursive(root, ['*.{txt,png}'])),
      ['a.pdf', 'b.docx', 'sub/d.pdf', 'sub/deep/e.docx']
    );
  }));

test('recursive-readdir honours negated ignore patterns (Minimatch.negate)', () =>
  withTree(async (root) => {
    // A negated ignore keeps only what the pattern names; the library guards it
    // with `!minimatcher.negate || stats.isFile()` so directories are still
    // descended into.
    assert.deepStrictEqual(relative(root, await recursive(root, ['!*.{pdf,docx}'])), [
      'a.pdf',
      'b.docx',
      'sub/d.pdf',
      'sub/deep/e.docx',
    ]);
  }));

test('recursive-readdir ignores a whole subtree by directory name', () =>
  withTree(async (root) => {
    assert.deepStrictEqual(relative(root, await recursive(root, ['sub'])), [
      'a.pdf',
      'b.docx',
      'c.txt',
    ]);
  }));

// --- supply-chain guard ------------------------------------------------------

test('no brace-expansion below the CVE-2026-14257 fix line survives in the lockfile', () => {
  const lock = require('./package-lock.json');
  const found = Object.entries(lock.packages)
    .filter(([name]) => name.endsWith('node_modules/brace-expansion'))
    .map(([name, meta]) => [name, meta.version]);

  assert.ok(found.length > 0, 'expected brace-expansion to be present in the lockfile');
  for (const [name, version] of found) {
    const [major, minor, patch] = version.split('.').map(Number);
    const fixed =
      major > 5 || (major === 5 && (minor > 0 || (minor === 0 && patch >= 8)));
    assert.ok(fixed, `${name} resolves to vulnerable brace-expansion ${version}`);
  }
});
