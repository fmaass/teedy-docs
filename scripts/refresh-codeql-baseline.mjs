#!/usr/bin/env node
// Remap dispositioned CodeQL baseline entries whose flagged sink line merely SHIFTED
// (a refactor added/removed lines above it) so the gate identity keyed on
// <ruleId>@<uri>:<startLine>:<startColumn> stops reddening on pure line drift.
//
// Why this exists: the CodeQL gate (scripts/codeql-gate.py) keys each dispositioned
// finding by its physical location. Any refactor that shifts a baselined sink line
// changes that identity and reds the gate until a human re-proves the finding and
// hand-edits the coordinate (this recurred repeatedly). This tool automates the
// SAFE subset of that maintenance: it relocates an entry ONLY when the exact sink
// line's bytes still exist at exactly one new location, so the disposition provably
// still applies to the same code.
//
// It is a CONTENT-MATCH remapper, NOT a scanner: it never runs CodeQL. It compares
// the current working tree against <old-rev> and, for each baselined entry whose
// file changed, finds the sink line's new coordinate by matching the exact bytes of
// the sink line (whitespace included).
//
// It REFUSES (nonzero exit, writes nothing) when it cannot remap safely:
//   * 0 matches  -> the triaged sink line changed or was removed; re-triage required.
//   * >1 matches -> ambiguous; the tool will not guess which copy is the sink.
// Refusal is all-or-nothing: if ANY entry cannot be cleanly remapped, the whole run
// fails and the baseline is left untouched (no partial writes).
//
// Usage:
//   node scripts/refresh-codeql-baseline.mjs <old-rev> [--baseline <path>] [--root <dir>] [--dry-run]
//
//   <old-rev>    git revision to diff the working tree against (e.g. the tag/commit
//                the current baseline coordinates were valid at).
//   --baseline   baseline JSON (default .github/baselines/codeql-known.json).
//   --root       repo root that entry URIs resolve against and that git runs in
//                (default: current directory).
//   --dry-run    report what would change; do not write.
//
// Run from the repository root.

import { readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join, resolve } from 'node:path';

function parseArgs(argv) {
  const opts = { oldRev: null, baseline: '.github/baselines/codeql-known.json', root: '.', dryRun: false };
  const rest = [];
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--baseline') opts.baseline = argv[++i];
    else if (a === '--root') opts.root = argv[++i];
    else if (a === '--dry-run') opts.dryRun = true;
    else rest.push(a);
  }
  opts.oldRev = rest[0] || null;
  return opts;
}

// Read as latin1 so every byte 0..255 round-trips to a distinct char: string
// comparison is then byte-exact, and split('\n') preserves any '\r' or leading/
// trailing whitespace verbatim ("compare raw bytes including whitespace").
function fileAtRev(root, rev, uri) {
  // Discard git's stderr: a missing path at <rev> is an expected condition the
  // caller turns into a clean refusal, not raw "fatal:" noise on our output.
  return execFileSync('git', ['-C', root, 'show', `${rev}:${uri}`], {
    maxBuffer: 64 * 1024 * 1024,
    stdio: ['ignore', 'pipe', 'ignore'],
  }).toString('latin1');
}

function currentFile(root, uri) {
  return readFileSync(join(root, uri)).toString('latin1');
}

// Parse "<ruleId>@<uri>:<startLine>:<startColumn>". The ruleId may contain '/' but
// no '@'; the uri contains no ':'; line and column are the trailing numeric fields.
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
  if (!opts.oldRev) {
    console.error('Usage: node scripts/refresh-codeql-baseline.mjs <old-rev> [--baseline <path>] [--root <dir>] [--dry-run]');
    process.exit(2);
  }
  const root = resolve(opts.root);
  const today = new Date().toISOString().slice(0, 10);

  let baseline;
  try {
    baseline = JSON.parse(readFileSync(opts.baseline, 'utf8'));
  } catch (e) {
    console.error(`Cannot read baseline ${opts.baseline}: ${e.message}`);
    process.exit(2);
  }
  const findings = baseline.findings;
  if (!Array.isArray(findings)) {
    console.error('Baseline findings must be an array');
    process.exit(2);
  }

  const refusals = [];
  const remaps = []; // { index, oldId, newId, uri, oldLine, newLine }
  let unchanged = 0;

  for (let i = 0; i < findings.length; i++) {
    const entry = findings[i];
    const parsed = parseId(entry.id);
    if (!parsed) {
      refusals.push(`finding ${i}: id "${entry.id}" is not <ruleId>@<uri>:<line>:<col>`);
      continue;
    }
    const { ruleId, uri, line: oldLine, col } = parsed;

    let oldContent;
    try {
      oldContent = fileAtRev(root, opts.oldRev, uri);
    } catch {
      refusals.push(`${entry.id}: cannot read ${uri} at ${opts.oldRev} (file absent there?) — re-triage required`);
      continue;
    }
    let newContent;
    try {
      newContent = currentFile(root, uri);
    } catch {
      refusals.push(`${entry.id}: cannot read current ${uri} (removed?) — re-triage required`);
      continue;
    }

    if (oldContent === newContent) {
      unchanged++;
      continue; // file byte-identical -> coordinate still valid, nothing to remap
    }

    const oldLines = oldContent.split('\n');
    if (oldLine < 1 || oldLine > oldLines.length) {
      refusals.push(`${entry.id}: startLine ${oldLine} is out of range for ${uri}@${opts.oldRev}`);
      continue;
    }
    const sinkLine = oldLines[oldLine - 1];

    // The sink line must be UNIQUE at <old-rev> as well: if the old file already had
    // a byte-identical twin, a single surviving current match may be that twin rather
    // than the real (now-changed) sink, so remapping would baseline an unrelated line
    // while the actually-changed finding goes undispositioned and slips the gate.
    // Only auto-remap when the sink line is unique in BOTH the old and the new file.
    const oldMatchCount = oldLines.reduce((n, l) => (l === sinkLine ? n + 1 : n), 0);
    if (oldMatchCount > 1) {
      refusals.push(`${entry.id}: sink line content is not unique at ${opts.oldRev} (${oldMatchCount} identical lines in ${uri}) — cannot disambiguate which the baseline referred to; re-triage required`);
      continue;
    }

    const newLines = newContent.split('\n');
    const matches = [];
    for (let k = 0; k < newLines.length; k++) {
      if (newLines[k] === sinkLine) matches.push(k + 1); // 1-based line numbers
    }

    if (matches.length === 0) {
      refusals.push(`${entry.id}: sink line content not found in current ${uri} — the triaged line changed or was removed; re-triage required`);
      continue;
    }
    if (matches.length > 1) {
      refusals.push(`${entry.id}: sink line content matches ${matches.length} lines (${matches.join(', ')}) in current ${uri} — ambiguous, refusing to guess`);
      continue;
    }

    const newLine = matches[0];
    // Belt-and-suspenders: the match is byte-identical by construction, but assert it.
    if (newLines[newLine - 1] !== sinkLine) {
      refusals.push(`${entry.id}: internal error, matched line ${newLine} is not byte-identical`);
      continue;
    }
    if (newLine === oldLine) {
      unchanged++;
      continue; // sink did not move (change was elsewhere in the file)
    }

    const newId = `${ruleId}@${uri}:${newLine}:${col}`;
    remaps.push({ index: i, oldId: entry.id, newId, uri, oldLine, newLine });
  }

  if (refusals.length > 0) {
    console.error('REFUSED — baseline left untouched:');
    for (const r of refusals) console.error(`  ✖ ${r}`);
    process.exit(1);
  }

  if (remaps.length === 0) {
    console.log(`No drift to remap (${unchanged} entr${unchanged === 1 ? 'y' : 'ies'} unchanged vs ${opts.oldRev}).`);
    return;
  }

  for (const r of remaps) {
    const entry = findings[r.index];
    entry.id = r.newId;
    const note = ` Coordinates refreshed ${today}: sink shifted from line ${r.oldLine} to ${r.newLine} vs ${opts.oldRev}; content verified byte-identical.`;
    entry.reason = (entry.reason || '') + note;
    console.log(`remap ${r.oldId}\n   -> ${r.newId}`);
  }

  if (opts.dryRun) {
    console.log(`\n[dry-run] ${remaps.length} entr${remaps.length === 1 ? 'y' : 'ies'} would be remapped; not writing.`);
    return;
  }

  writeFileSync(opts.baseline, JSON.stringify(baseline, null, 2) + '\n');
  console.log(`\nRemapped ${remaps.length} entr${remaps.length === 1 ? 'y' : 'ies'}; wrote ${opts.baseline}.`);
}

main();
