#!/usr/bin/env node
// Fifth "mirror" gate: CodeQL-baseline coordinate drift.
//
// The CodeQL gate (scripts/codeql-gate.py) suppresses each triaged finding by a
// LOCATION-KEYED identity: <ruleId>@<uri>:<startLine>:<startColumn> in
// .github/baselines/codeql-known.json. Adding or removing lines ABOVE a triaged
// sink shifts that line, so the pinned coordinate no longer names the sink and the
// suppression silently stops applying -- the first signal being a red CI run of the
// codeql gate, then a manual coordinate remap. This gate catches that drift BEFORE
// the push, alongside the other four mirror gates.
//
// Comparison is a STORED per-entry fingerprint, verified purely against the working
// tree -- no git history is consulted. Every baseline entry carries `sink_line`: the
// verbatim source line that was at its coordinate when the coordinate was last pinned
// (written by scripts/refresh-codeql-baseline.mjs on every remap). This gate reads the
// working-tree file at the entry's line and compares it, byte for byte, to `sink_line`.
// A mismatch means the sink moved and the coordinate is stale. A missing `sink_line`
// FAILS CLOSED (the entry is named) rather than passing unchecked.
//
// Why stored, not git-derived: deriving the expected content from "the last commit that
// touched the baseline" is unsound. A metadata-only baseline edit (e.g. bumping an
// `expires` date -- exactly what the expiry tooling does) advances that commit to source
// that may already have shifted, so the derivation samples stale content and false-greens
// permanently; a shallow / path-limited clone can pick a boundary commit and false-green
// the same way. A stored fingerprint has neither failure mode.
//
// Known limit (inherent to location-keying): if a shift happens to leave a byte-identical
// line at the pinned coordinate (e.g. a duplicated line), the content matches and the
// drift is invisible to this gate. The codeql gate against live SARIF remains the backstop.
//
// Usage:
//   node scripts/check-codeql-baseline-drift.mjs [--baseline <path>] [--root <dir>]
//
//   --baseline  baseline JSON (default .github/baselines/codeql-known.json).
//   --root      repo root that entry URIs resolve against (default: current directory).
//
// Run from the repository root.
//
// Exit codes:
//   0  every baseline entry's sink line is byte-identical to its stored fingerprint.
//   1  one or more entries drifted, lack a fingerprint, or have an unverifiable id.
//   2  a precondition failure: unreadable or invalid baseline.

import { readFileSync } from 'node:fs';
import { isAbsolute, join, resolve } from 'node:path';

function parseArgs(argv) {
  const opts = { baseline: '.github/baselines/codeql-known.json', root: '.' };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--baseline') opts.baseline = argv[++i];
    else if (a === '--root') opts.root = argv[++i];
  }
  return opts;
}

// Read as latin1 so every byte 0..255 round-trips to a distinct char: string
// comparison is byte-exact and split('\n') preserves any '\r' or leading/trailing
// whitespace verbatim -- identical semantics to refresh-codeql-baseline.mjs, so the
// fingerprint it stores and the line this gate reads are compared on the same footing.
function currentFile(root, uri) {
  return readFileSync(join(root, uri)).toString('latin1');
}

// Parse "<ruleId>@<uri>:<startLine>:<startColumn>" -- same grammar as the gate and the
// refresh tool. The ruleId may contain '/' but no '@'; the uri contains no ':'.
function parseId(id) {
  const at = id.indexOf('@');
  if (at < 0) return null;
  const ruleId = id.slice(0, at);
  const rest = id.slice(at + 1);
  const m = rest.match(/^(.*):(\d+):(\d+)$/);
  if (!m) return null;
  return { ruleId, uri: m[1], line: Number(m[2]), col: Number(m[3]) };
}

function main() {
  const opts = parseArgs(process.argv.slice(2));
  const root = resolve(opts.root);
  const baselineAbs = isAbsolute(opts.baseline) ? opts.baseline : join(root, opts.baseline);

  let baseline;
  try {
    baseline = JSON.parse(readFileSync(baselineAbs, 'utf8'));
  } catch (e) {
    console.error(`Cannot read baseline ${baselineAbs}: ${e.message}`);
    process.exit(2);
  }
  const findings = baseline.findings;
  if (!Array.isArray(findings)) {
    console.error('Baseline findings must be an array');
    process.exit(2);
  }

  console.log(`Comparing ${findings.length} baseline sink${findings.length === 1 ? '' : 's'} against their stored sink_line fingerprints (working tree only).`);

  const drifted = [];
  for (const entry of findings) {
    const parsed = parseId(entry.id);
    if (!parsed) {
      drifted.push({ id: entry.id, why: 'id is not <ruleId>@<uri>:<line>:<col>' });
      continue;
    }
    if (typeof entry.sink_line !== 'string') {
      drifted.push({ id: entry.id, why: 'no stored sink_line fingerprint (fail closed) — run refresh-codeql-baseline.mjs to establish it' });
      continue;
    }
    const { uri, line } = parsed;

    let actual;
    try {
      const curLines = currentFile(root, uri).split('\n');
      if (line < 1 || line > curLines.length) {
        drifted.push({ id: entry.id, why: `line ${line} is past the end of ${uri} (file shrank)`, expected: entry.sink_line });
        continue;
      }
      actual = curLines[line - 1];
    } catch {
      drifted.push({ id: entry.id, why: `cannot read ${uri} (removed?)`, expected: entry.sink_line });
      continue;
    }

    if (actual !== entry.sink_line) {
      drifted.push({ id: entry.id, why: 'sink line shifted', expected: entry.sink_line, actual });
    }
  }

  if (drifted.length === 0) {
    console.log('No CodeQL baseline drift: every pinned sink is byte-identical to its stored fingerprint.');
    return;
  }

  console.error(`\nCodeQL baseline drift: ${drifted.length} entr${drifted.length === 1 ? 'y' : 'ies'} no longer name their triaged sink.`);
  for (const d of drifted) {
    console.error(`  ! ${d.id}`);
    console.error(`      ${d.why}`);
    if (d.expected !== undefined) console.error(`      expected (sink_line): ${JSON.stringify(d.expected)}`);
    if (d.actual !== undefined) console.error(`      current line:         ${JSON.stringify(d.actual)}`);
  }
  console.error('\nThe location-keyed suppression no longer applies at these coordinates, so the codeql gate will red on push.');
  console.error('Remap with:  node scripts/refresh-codeql-baseline.mjs <rev-where-these-coordinates-were-valid>');
  console.error('(find that rev with:  git log -1 --format=%H -- .github/baselines/codeql-known.json — it re-pins the coordinates and rewrites sink_line.)');
  process.exit(1);
}

main();
