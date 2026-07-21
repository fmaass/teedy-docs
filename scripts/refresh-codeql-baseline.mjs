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
// On every remap it also writes the matched sink line verbatim into the entry's
// `sink_line` field -- the fingerprint the local pre-push drift gate
// (scripts/check-codeql-baseline-drift.mjs) compares against. A remap whose fingerprint
// cannot be established is refused (left unchanged) rather than written unverifiable.
//
// Per-entry degradation (NOT all-or-nothing): each entry is judged independently.
//   * A byte-identical single-match shift is remapped automatically.
//   * An entry that cannot be remapped safely does NOT abort the run and does NOT
//     block the entries that can. It is left UNCHANGED and reported for manual
//     follow-up. The reasons an entry cannot auto-remap:
//       - 0 matches  -> the triaged sink line changed or was removed; re-triage.
//       - >1 matches -> ambiguous; the tool will not guess which copy is the sink.
//       - the sink line was already non-unique at <old-rev> (cannot tell which copy
//         the baseline referred to).
//       - the id is malformed, or the file is absent at <old-rev> or in the tree.
//   With --interactive, each such entry prompts for a new start line (validated to be
//   byte-identical to the triaged sink) or is skipped; without it, they are skipped.
//
// The clean remaps are still written even when some entries were skipped. Exit 3
// signals "some entries need manual follow-up" so CI/humans notice, while the safe
// remaps have already landed.
//
// Usage:
//   node scripts/refresh-codeql-baseline.mjs <old-rev> [--baseline <path>] [--root <dir>] [--dry-run] [--interactive]
//
//   <old-rev>       git revision to diff the working tree against (e.g. the tag/commit
//                   the current baseline coordinates were valid at).
//   --baseline      baseline JSON (default .github/baselines/codeql-known.json).
//   --root          repo root that entry URIs resolve against and that git runs in
//                   (default: current directory).
//   --dry-run       report what would change; do not write.
//   --interactive   for each entry that cannot auto-remap, print a prompt and read ONE
//                   line from stdin: a new start line (accepted only if byte-identical
//                   to the triaged sink) or 's'/blank to skip. Prompts appear one at a
//                   time (a piped answer per prompt works too, e.g. printf '456\ns\n').
//
// Run from the repository root.
//
// Exit codes:
//   0  everything is up to date or was remapped cleanly; nothing needs manual work.
//   2  a precondition/structural failure: missing or unresolvable <old-rev>, or an
//      unreadable/invalid baseline. Distinct from the per-entry manual case below.
//   3  one or more entries could not be auto-remapped and were skipped (or, under
//      --interactive, left unresolved). Any clean remaps were still written.

import { readFileSync, writeFileSync, readSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join, resolve } from 'node:path';

function parseArgs(argv) {
  const opts = { oldRev: null, baseline: '.github/baselines/codeql-known.json', root: '.', dryRun: false, interactive: false };
  const rest = [];
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--baseline') opts.baseline = argv[++i];
    else if (a === '--root') opts.root = argv[++i];
    else if (a === '--dry-run') opts.dryRun = true;
    else if (a === '--interactive') opts.interactive = true;
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
  // caller turns into a clean skip, not raw "fatal:" noise on our output.
  return execFileSync('git', ['-C', root, 'show', `${rev}:${uri}`], {
    maxBuffer: 64 * 1024 * 1024,
    stdio: ['ignore', 'pipe', 'ignore'],
  }).toString('latin1');
}

function currentFile(root, uri) {
  return readFileSync(join(root, uri)).toString('latin1');
}

// Whether <rev> resolves to a commit in the repo at <root>. Validated ONCE up front so
// a typo'd or unavailable revision is a clear precondition error (exit 2) rather than
// showing up as every entry "failing to read at <rev>" (which reads as manual, exit 3).
function revExists(root, rev) {
  try {
    execFileSync('git', ['-C', root, 'rev-parse', '--verify', '--quiet', `${rev}^{commit}`], {
      stdio: ['ignore', 'ignore', 'ignore'],
    });
    return true;
  } catch {
    return false;
  }
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

// Judge a single entry against <old-rev>. Returns one of:
//   { kind: 'unchanged' }
//   { kind: 'remap', index, oldId, newId, uri, oldLine, newLine }
//   { kind: 'manual', index, id, why, sinkLine, ruleId?, uri?, col?, newLines? }
// A 'manual' verdict never aborts the run; the caller reports/skips it (or prompts).
function evaluateEntry(findings, index, root, oldRev) {
  const entry = findings[index];
  const parsed = parseId(entry.id);
  if (!parsed) {
    return { kind: 'manual', index, id: entry.id, why: `id "${entry.id}" is not <ruleId>@<uri>:<line>:<col>`, sinkLine: null };
  }
  const { ruleId, uri, line: oldLine, col } = parsed;

  let oldContent;
  try {
    oldContent = fileAtRev(root, oldRev, uri);
  } catch {
    return { kind: 'manual', index, id: entry.id, why: `cannot read ${uri} at ${oldRev} (file absent there?)`, sinkLine: null };
  }
  let newContent;
  try {
    newContent = currentFile(root, uri);
  } catch {
    return { kind: 'manual', index, id: entry.id, why: `cannot read current ${uri} (removed?)`, sinkLine: null };
  }

  if (oldContent === newContent) {
    return { kind: 'unchanged' }; // file byte-identical -> coordinate still valid
  }

  const oldLines = oldContent.split('\n');
  if (oldLine < 1 || oldLine > oldLines.length) {
    return { kind: 'manual', index, id: entry.id, why: `startLine ${oldLine} is out of range for ${uri}@${oldRev}`, sinkLine: null };
  }
  const sinkLine = oldLines[oldLine - 1];
  const newLines = newContent.split('\n');

  // The sink line must be UNIQUE at <old-rev> too: if the old file already had a
  // byte-identical twin, a single surviving current match may be that twin rather than
  // the real (now-changed) sink, so remapping would baseline an unrelated line while
  // the actually-changed finding goes undispositioned and slips the gate. Only
  // auto-remap when the sink line is unique in BOTH the old and the new file.
  const oldMatchCount = oldLines.reduce((n, l) => (l === sinkLine ? n + 1 : n), 0);
  if (oldMatchCount > 1) {
    return { kind: 'manual', index, id: entry.id, ruleId, uri, col, oldLine, newLines, sinkLine,
      why: `sink line content is not unique at ${oldRev} (${oldMatchCount} identical lines in ${uri}) — cannot disambiguate which the baseline referred to` };
  }

  const matches = [];
  for (let k = 0; k < newLines.length; k++) {
    if (newLines[k] === sinkLine) matches.push(k + 1); // 1-based line numbers
  }

  if (matches.length === 0) {
    return { kind: 'manual', index, id: entry.id, ruleId, uri, col, oldLine, newLines, sinkLine,
      why: `sink line content not found in current ${uri} — the triaged line changed or was removed` };
  }
  if (matches.length > 1) {
    return { kind: 'manual', index, id: entry.id, ruleId, uri, col, oldLine, newLines, sinkLine,
      why: `sink line content matches ${matches.length} lines (${matches.join(', ')}) in current ${uri} — ambiguous` };
  }

  const newLine = matches[0];
  if (newLine === oldLine) {
    return { kind: 'unchanged' }; // sink did not move (change was elsewhere in the file)
  }
  // sinkLine is the byte-identical matched content -> it is the fingerprint written
  // alongside the new coordinate so the drift gate can verify it locally.
  return { kind: 'remap', index, oldId: entry.id, newId: `${ruleId}@${uri}:${newLine}:${col}`, uri, oldLine, newLine, sinkLine };
}

// Sleep synchronously — used only to avoid a busy spin while a TTY has no input yet.
function sleepMs(ms) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

// Read ONE line from stdin synchronously so each interactive prompt is shown and
// answered one at a time (rather than reading the whole stream up front, which looks
// hung at a TTY). Returns the line without its newline, or null at end of input.
function readLineSync() {
  const buf = Buffer.alloc(1);
  let line = '';
  let sawByte = false;
  for (;;) {
    let n;
    try {
      n = readSync(0, buf, 0, 1, null);
    } catch (err) {
      if (err.code === 'EAGAIN') { sleepMs(20); continue; } // TTY not ready yet
      if (err.code === 'EOF') break;
      throw err;
    }
    if (n === 0) break; // end of input
    sawByte = true;
    const ch = buf[0];
    if (ch === 0x0a) return line; // newline terminates the line
    if (ch === 0x0d) continue;    // ignore CR in CRLF input
    line += String.fromCharCode(ch);
  }
  return sawByte ? line : null; // trailing unterminated line, or null at EOF
}

// Try to resolve one manual entry interactively. Returns a remap descriptor if the
// answer is a valid byte-identical line, else null (skip).
function resolveInteractively(manual) {
  if (manual.sinkLine == null || !manual.newLines) {
    process.stderr.write(`  ? ${manual.id}: ${manual.why}\n    (no recoverable sink content — skipping)\n`);
    return null;
  }
  const candidates = manual.newLines
    .map((l, k) => (l === manual.sinkLine ? k + 1 : null))
    .filter((n) => n !== null);
  process.stderr.write(
    `  ? ${manual.id}: ${manual.why}\n` +
    `    sink line: ${JSON.stringify(manual.sinkLine)}\n` +
    `    matching lines in current file: ${candidates.length ? candidates.join(', ') : '(none)'}\n` +
    `    enter new start line, or 's'/blank to skip: `,
  );
  const answer = (readLineSync() || '').trim();
  if (answer === '' || answer.toLowerCase() === 's') {
    process.stderr.write('    skipped.\n');
    return null;
  }
  const line = Number(answer);
  if (!Number.isInteger(line) || line < 1 || line > manual.newLines.length) {
    process.stderr.write(`    "${answer}" is not a valid line in range — skipping.\n`);
    return null;
  }
  if (manual.newLines[line - 1] !== manual.sinkLine) {
    process.stderr.write(`    line ${line} is not byte-identical to the triaged sink — skipping.\n`);
    return null;
  }
  return { index: manual.index, oldId: manual.id, newId: `${manual.ruleId}@${manual.uri}:${line}:${manual.col}`, uri: manual.uri, oldLine: manual.oldLine, newLine: line, sinkLine: manual.sinkLine };
}

function main() {
  const opts = parseArgs(process.argv.slice(2));
  if (!opts.oldRev) {
    console.error('Usage: node scripts/refresh-codeql-baseline.mjs <old-rev> [--baseline <path>] [--root <dir>] [--dry-run] [--interactive]');
    process.exit(2);
  }
  const root = resolve(opts.root);
  if (!revExists(root, opts.oldRev)) {
    console.error(`old-rev "${opts.oldRev}" does not resolve to a commit in ${root}`);
    process.exit(2);
  }
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

  const remaps = [];  // { index, oldId, newId, uri, oldLine, newLine }
  const manuals = []; // entries that could not auto-remap
  let unchanged = 0;

  for (let i = 0; i < findings.length; i++) {
    const verdict = evaluateEntry(findings, i, root, opts.oldRev);
    if (verdict.kind === 'unchanged') unchanged++;
    else if (verdict.kind === 'remap') remaps.push(verdict);
    else manuals.push(verdict);
  }

  // Interactive resolution: give each manual entry one chance to be remapped by hand.
  const stillManual = [];
  if (opts.interactive && manuals.length > 0) {
    process.stderr.write(`Resolving ${manuals.length} entr${manuals.length === 1 ? 'y' : 'ies'} that could not auto-remap:\n`);
    for (const manual of manuals) {
      const resolved = resolveInteractively(manual);
      if (resolved) remaps.push(resolved);
      else stillManual.push(manual);
    }
  } else {
    stillManual.push(...manuals);
  }

  // Refuse to write any remap whose fingerprint could not be established: the drift
  // gate REQUIRES a sink_line, so writing a new coordinate without one would move the
  // entry to an unverifiable state. In practice every remap carries the byte-identical
  // matched content, so this only guards a future code path.
  const applicable = [];
  for (const r of remaps) {
    if (typeof r.sinkLine !== 'string') {
      stillManual.push({ id: r.oldId, why: 'cannot establish the sink_line fingerprint for the new coordinate — left unchanged' });
    } else {
      applicable.push(r);
    }
  }

  // Apply the remaps (auto + interactively resolved) to the baseline object, writing
  // the new coordinate AND its sink_line fingerprint.
  for (const r of applicable) {
    const entry = findings[r.index];
    entry.id = r.newId;
    entry.sink_line = r.sinkLine;
    const note = ` Coordinates refreshed ${today}: sink shifted from line ${r.oldLine} to ${r.newLine} vs ${opts.oldRev}; content verified byte-identical.`;
    entry.reason = (entry.reason || '') + note;
  }

  // Report.
  for (const r of applicable) {
    console.log(`remap ${r.oldId}\n   -> ${r.newId}`);
  }
  if (stillManual.length > 0) {
    console.error(`\nMANUAL FOLLOW-UP NEEDED — ${stillManual.length} entr${stillManual.length === 1 ? 'y' : 'ies'} left unchanged:`);
    for (const m of stillManual) console.error(`  ! ${m.id}: ${m.why}`);
  }

  if (applicable.length === 0 && stillManual.length === 0) {
    console.log(`No drift to remap (${unchanged} entr${unchanged === 1 ? 'y' : 'ies'} unchanged vs ${opts.oldRev}).`);
    return;
  }

  if (opts.dryRun) {
    console.log(`\n[dry-run] ${applicable.length} entr${applicable.length === 1 ? 'y' : 'ies'} would be remapped; ${stillManual.length} need manual follow-up; not writing.`);
  } else if (applicable.length > 0) {
    writeFileSync(opts.baseline, JSON.stringify(baseline, null, 2) + '\n');
    console.log(`\nRemapped ${applicable.length} entr${applicable.length === 1 ? 'y' : 'ies'}; wrote ${opts.baseline}.${stillManual.length ? ` ${stillManual.length} still need manual follow-up.` : ''}`);
  } else {
    console.log(`\nNothing to write; ${stillManual.length} entr${stillManual.length === 1 ? 'y' : 'ies'} need manual follow-up.`);
  }

  // Exit 3 when any entry still needs a human; the clean remaps have already landed.
  if (stillManual.length > 0) process.exit(3);
}

main();
