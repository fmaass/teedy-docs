// Tests for scripts/repair-mojibake.mjs.
//
// The value-level and argument-validation tests are pure and need nothing but node. The database
// tests exercise the execution contract (hardcoded write scope, re-derivation from the database,
// cluster binding, three-state write, drift abort, idempotency) and are opt-in: set
// MOJIBAKE_TEST_PG=1 and point the standard libpq variables (PGHOST/PGPORT/PGUSER/PGDATABASE) at a
// THROWAWAY database. They create and drop their own tables and touch nothing else.
//
// Run: node --test scripts/repair-mojibake.test.mjs   (or scripts/test-repair-mojibake.sh)
//
// Damaged inputs are written as explicit \uXXXX escapes. Half the bytes of real mojibake land in
// the C1 block (U+0080-U+009F), which is invisible in every editor; a pasted literal would rot
// without anyone noticing. The expected outputs are ordinary readable literals.

import { test, describe, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  assess, classifyValue, hasMojibakePattern, reverseMojibake, EXECUTE_SQL,
} from './repair-mojibake.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const TOOL = path.join(here, 'repair-mojibake.mjs');

const KOERPER_BROKEN = 'K\u00C3\u00B6rper.pdf';           // Körper.pdf
const AUSFUEHRUNG_BROKEN = 'Ausf\u00C3\u00BChrung.pdf';   // Ausführung.pdf
const MAASS_BROKEN = 'Maa\u00C3\u009F.pdf';               // Maaß.pdf
const ENDASH_BROKEN = 'Rechnung \u00E2\u0080\u0093 Rasenpflege.pdf';// Rechnung – Rasenpflege.pdf
const CJK_BROKEN = '\u00E6\u0097\u00A5\u00E6\u009C\u00AC.pdf';// 日本.pdf
const DOUBLE_BROKEN = 'K\u00C3\u0083\u00C2\u00B6rper.pdf';// one layer above KOERPER_BROKEN
const CONTROL_BROKEN = 'x\u00C2\u0085y';                  // reverses to U+0085
const DANGLING_BROKEN = 'x\u00C3\u00A9\u00C3';            // valid pair, then a dangling lead byte
const CP1252_BROKEN = 'Gr\u00C3\u00B6\u00C3\u0178e.pdf';  // Größe.pdf with 0x9F as cp1252 U+0178

const CUTOFF = '2026-07-19T00:00:00.000';
const PRE = '2026-04-01T10:00:00.000';
const POST = '2026-08-01T10:00:00.000';

describe('detection and reversal', () => {
  test('reverses German umlaut damage', () => {
    assert.equal(classifyValue(KOERPER_BROKEN).reversed, 'Körper.pdf');
    assert.equal(classifyValue(AUSFUEHRUNG_BROKEN).reversed, 'Ausführung.pdf');
  });

  test('reverses sharp s, whose continuation byte is an invisible control', () => {
    const r = classifyValue(MAASS_BROKEN);
    assert.equal(r.suspect, true);
    assert.equal(r.reversed, 'Maaß.pdf');
    assert.deepEqual(r.flags, []);
  });

  test('reverses punctuation damage (en dash)', () => {
    assert.equal(classifyValue(ENDASH_BROKEN).reversed, 'Rechnung – Rasenpflege.pdf');
  });

  test('reverses CJK damage', () => {
    assert.equal(classifyValue(CJK_BROKEN).reversed, '日本.pdf');
  });

  test('leaves already-clean values alone', () => {
    for (const clean of ['Bericht.pdf', 'Zürich.pdf', 'Größe 2026.pdf', '日本.pdf', 'Maaß.pdf',
      'Rechnung – Rasenpflege.pdf', '']) {
      assert.equal(classifyValue(clean).suspect, false, `${clean} must not be suspect`);
    }
  });

  test('does not match legitimate text that merely contains A-tilde', () => {
    // Latin letters after the lead char are not UTF-8 continuation bytes, so nothing looks
    // double-encoded and the strict round trip is never even attempted.
    for (const real of ['São Paulo.pdf', 'Águas de Março.pdf', 'Instalação.pdf',
      'Café à Paris.pdf', 'Ãngstrom.pdf']) {
      assert.equal(hasMojibakePattern(real), false, `${real} must not match the pattern`);
      assert.equal(classifyValue(real).suspect, false);
    }
  });

  test('does not match a value whose bytes are not valid UTF-8', () => {
    // A valid pair followed by a dangling lead byte: it looks double-encoded but is not.
    // The strict decoder is what rejects it.
    assert.equal(hasMojibakePattern(DANGLING_BROKEN), true);
    assert.equal(reverseMojibake(DANGLING_BROKEN), null);
    assert.equal(classifyValue(DANGLING_BROKEN).suspect, false);
  });

  test('does not touch cp1252-flavoured damage, which is not losslessly reversible', () => {
    // A cp1252 rendering maps 0x9F to U+0178, above the Latin-1 range, so the value cannot be
    // encoded back to bytes at all -- the same guard the runtime fix applies.
    assert.equal(reverseMojibake(CP1252_BROKEN), null);
    assert.equal(classifyValue(CP1252_BROKEN).suspect, false);
  });

  test('peels exactly one layer off a double-encoded value and says so', () => {
    const once = classifyValue(DOUBLE_BROKEN);
    assert.equal(once.reversed, KOERPER_BROKEN);        // still damaged, one layer left
    assert.ok(once.flags.includes('residual_mojibake'));
    // A later dry run would list the repaired value again as a NEW candidate with a new
    // before/after pair. That is not an idempotency break: idempotency is defined against a
    // manifest, and a second layer needs a second, separately approved manifest.
    const twice = classifyValue(once.reversed);
    assert.equal(twice.reversed, 'Körper.pdf');
    assert.deepEqual(twice.flags, []);
  });

  test('flags a reversal that yields control characters instead of readable text', () => {
    const r = classifyValue(CONTROL_BROKEN);
    assert.equal(r.suspect, true);
    assert.ok(r.flags.includes('implausible_reversal'));
  });

  test('the classification limit is a flag-free candidate, by design', () => {
    // A file genuinely named "Ã¼.pdf" is byte-identical to mojibake for "ü.pdf". The tool cannot
    // tell them apart and does not pretend to -- which is why nothing is written without an
    // approved manifest.
    assert.equal(classifyValue('Ã¼.pdf').reversed, 'ü.pdf');
  });

  test('a reversal that does not change the value is not a candidate', () => {
    assert.equal(classifyValue('plain-ascii.pdf').suspect, false);
    assert.equal(reverseMojibake('plain-ascii.pdf'), 'plain-ascii.pdf');
  });
});

describe('eligibility rule', () => {
  const row = (over = {}) => assess({
    table: 't_file', column: 'fil_name_c', value: KOERPER_BROKEN,
    length: KOERPER_BROKEN.length, firstEvidence: PRE, cutoff: CUTOFF, ...over,
  });

  test('an in-scope, pre-cutoff, cleanly reversible row is eligible', () => {
    const a = row();
    assert.equal(a.eligible, true);
    assert.deepEqual(a.flags, []);
    assert.equal(a.reversed, 'Körper.pdf');
  });

  test('a column outside the hardcoded repair scope is never eligible', () => {
    for (const [table, column] of [['t_audit_log', 'log_message_c'], ['t_comment', 'com_content_c'],
      ['zz_mojibake_test', 'name']]) {
      const a = row({ table, column });
      assert.ok(a.flags.includes('out_of_scope'), `${table}.${column} must be out of scope`);
      assert.equal(a.eligible, false);
    }
  });

  test('evidence at or after the cutoff is never eligible', () => {
    const a = row({ firstEvidence: POST });
    assert.ok(a.flags.includes('post_cutoff_evidence'));
    assert.equal(a.eligible, false);
    assert.equal(row({ firstEvidence: CUTOFF }).eligible, false, 'the cutoff itself is not "before"');
  });

  test('no evidence at all is never eligible', () => {
    const a = row({ firstEvidence: null });
    assert.ok(a.flags.includes('no_provenance'));
    assert.equal(a.eligible, false);
  });

  test('an undamaged value is not a hit at all', () => {
    assert.equal(row({ value: 'Körper.pdf' }), null);
  });
});

function runTool(args, env = {}) {
  return spawnSync(process.execPath, [TOOL, ...args], {
    encoding: 'utf8', env: { ...process.env, PGCLIENTENCODING: 'UTF8', ...env },
  });
}

describe('connection argument validation', () => {
  test('refuses a connection URI in place of a database name', () => {
    const res = runTool(['--dbname', 'postgresql://teedy:hunter2@db.example/teedy']);
    assert.equal(res.status, 2);
    assert.match(res.stderr, /connection URI or conninfo/);
    assert.doesNotMatch(res.stderr, /hunter2/, 'the refusal must not echo the credential');
  });

  test('refuses a conninfo string carrying a password', () => {
    const res = runTool(['--dbname', 'dbname=teedy password=hunter2']);
    assert.equal(res.status, 2);
    assert.match(res.stderr, /connection URI or conninfo/);
    assert.doesNotMatch(res.stderr, /hunter2/);
  });

  test('refuses a credential-bearing PGDATABASE from the environment', () => {
    const res = runTool([], { PGDATABASE: 'postgresql://teedy:hunter2@db.example/teedy' });
    assert.equal(res.status, 2);
    assert.match(res.stderr, /PGDATABASE/);
    assert.doesNotMatch(res.stderr, /hunter2/);
  });

  test('refuses a host or user that is not a plain name', () => {
    assert.equal(runTool(['--host', 'host=db password=x']).status, 2);
    assert.equal(runTool(['--user', 'teedy@example.com']).status, 2);
  });

  test('a blank flag is refused instead of falling through to the environment', () => {
    // The bug this covers: an empty --dbname skipped validation, psql then took PGDATABASE, and
    // whatever was in it reached the persisted artifact unchecked.
    for (const flag of ['--dbname', '--host', '--user', '--port']) {
      const res = runTool([flag, ''], { PGDATABASE: 'postgresql://teedy:hunter2@db.example/teedy' });
      assert.equal(res.status, 2, `${flag} '' must be refused`);
      assert.match(res.stderr, /is empty/);
      assert.doesNotMatch(res.stderr, /hunter2/);
    }
    for (const flag of ['--dbname', '--host', '--user']) {
      assert.equal(runTool([flag, '   ']).status, 2, `${flag} whitespace must be refused`);
    }
  });

  test('refuses the connection-service variables outright', () => {
    // A service file can supply host, user, database AND password without any of them passing
    // through this tool's validation.
    for (const [k, v] of [['PGSERVICE', 'repairsvc'], ['PGSERVICEFILE', '/tmp/pg_service.conf'],
      ['PGHOSTADDR', '127.0.0.1']]) {
      const res = runTool([], { [k]: v });
      assert.equal(res.status, 2, `${k} must be refused`);
      assert.match(res.stderr, new RegExp(k));
    }
  });

  test('refuses a port that is not a number', () => {
    assert.equal(runTool(['--port', '55432x']).status, 2);
  });
});

// ---------------------------------------------------------------------------------------------
// Database-backed contract tests (opt-in).
// ---------------------------------------------------------------------------------------------

const DB_ENABLED = process.env.MOJIBAKE_TEST_PG === '1';
const lit = (s) => `'${s.replace(/'/g, "''")}'`;

function psql(sql) {
  const res = spawnSync('psql', ['-X', '-q', '-A', '-t', '-v', 'ON_ERROR_STOP=1', '-c', sql],
    { encoding: 'utf8', env: { ...process.env, PGCLIENTENCODING: 'UTF8' } });
  assert.equal(res.status, 0, `psql failed: ${res.stderr}`);
  return res.stdout.trim();
}

function name(id) {
  return psql(`select fil_name_c from t_file where fil_id_c = '${id}'`);
}

function writeManifest(rows, dir, source) {
  const file = path.join(dir, `manifest-${Math.random().toString(36).slice(2)}.json`);
  fs.writeFileSync(file, JSON.stringify({ layer: 'candidate-manifest', source, rows }, null, 2));
  return file;
}

function newest(dir, prefix) {
  const f = fs.readdirSync(dir).filter((n) => n.startsWith(prefix)).sort().pop();
  assert.ok(f, `no ${prefix}* file in ${dir}`);
  return { path: path.join(dir, f), body: JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8')) };
}

const fileRow = (rid, before, after, flags = []) => ({
  schema: 'public', table: 't_file', column: 'fil_name_c', pk_column: 'fil_id_c',
  rid, before, after, flags,
});

const planRow = (rid, before, after) => ({
  ord: 0, sch: 'public', tbl: 't_file', col: 'fil_name_c', pkcol: 'fil_id_c',
  rid, tscol: 'fil_createdate_d', val_before: before, val_after: after,
});

/**
 * Drives EXECUTE_SQL directly, bypassing the pre-flight entirely: this is how the in-transaction
 * gates are shown to hold on their own rather than because a check outside already passed.
 */
function runExecuteSql(plan, { sysid, db, cutoff = CUTOFF } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mojibake-sql-test-'));
  const sqlFile = path.join(dir, 'execute.sql');
  fs.writeFileSync(sqlFile, EXECUTE_SQL);
  const res = spawnSync('psql', ['-X', '-q', '-A', '-t', '-v', 'ON_ERROR_STOP=1',
    '-v', `plan=${JSON.stringify(plan.map((p, i) => ({ ...p, ord: i })))}`,
    '-v', `sysid=${sysid ?? psql('select system_identifier::text from pg_control_system()')}`,
    '-v', `db=${db ?? psql('select current_database()')}`,
    '-v', `cutoff=${cutoff}`,
    '-f', sqlFile],
  { encoding: 'utf8', env: { ...process.env, PGCLIENTENCODING: 'UTF8' } });
  fs.rmSync(dir, { recursive: true, force: true });
  return res;
}

describe('execution contract', {
  skip: DB_ENABLED ? false : 'set MOJIBAKE_TEST_PG=1 and PG* to a throwaway database',
}, () => {
  let tmp;
  let source;   // the cluster identity stamped by a real dry run

  before(() => {
    tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'mojibake-test-'));
    // These tests DROP AND RECREATE t_file and t_audit_log, so they must never be aimed at a real
    // Teedy database, throwaway copy or not. T_DOCUMENT is not part of the fixture: its presence
    // means this is a Teedy schema and the run stops before touching anything.
    const teedy = psql("select count(*) from information_schema.tables"
      + " where table_schema = 'public' and table_name = 't_document'");
    assert.equal(teedy, '0', `${psql('select current_database()')} looks like a real Teedy database:`
      + ' point PGDATABASE at an empty throwaway database instead');

    // A Teedy-shaped fixture: only these table/column names are inside the tool's hardcoded write
    // scope, and only these tables carry the server-set timestamps it accepts as evidence.
    psql(`drop table if exists t_file, t_audit_log, zz_mojibake_test;
      create table t_file (fil_id_c text primary key, fil_name_c text, fil_createdate_d timestamp);
      create table t_audit_log (log_id_c text primary key, log_identity_c text,
        log_classentity_c text, log_type_c text, log_message_c text, log_createdate_d timestamp);
      create table zz_mojibake_test (id text primary key, name text);
      insert into t_file values
        ('f1', ${lit(KOERPER_BROKEN)},     timestamp '2026-04-01 10:00:00'),
        ('f2', 'Bericht.pdf',              timestamp '2026-04-01 10:00:00'),
        ('f3', ${lit(AUSFUEHRUNG_BROKEN)}, timestamp '2026-08-01 10:00:00'),
        ('f4', ${lit(MAASS_BROKEN)},       null),
        ('f5', ${lit(DOUBLE_BROKEN)},      timestamp '2026-04-02 10:00:00'),
        ('f6', ${lit(ENDASH_BROKEN)},      timestamp '2026-04-03 10:00:00'),
        ('f7', ${lit(CONTROL_BROKEN)},     timestamp '2026-04-04 10:00:00'),
        ('f8', ${lit(KOERPER_BROKEN)},     timestamp '2026-04-05 10:00:00');
      insert into t_audit_log values
        ('a1', 'f1', 'File', 'CREATE', ${lit(KOERPER_BROKEN)}, timestamp '2026-04-01 10:00:00');
      insert into zz_mojibake_test values ('z1', ${lit(CJK_BROKEN)});`);
  });

  after(() => {
    psql('drop table if exists t_file, t_audit_log, zz_mojibake_test');
    fs.rmSync(tmp, { recursive: true, force: true });
  });

  test('dry run separates the inventory from the manifest and exits 1 on uncleared damage', () => {
    const res = runTool(['--out-dir', tmp]);
    // f3 (post-cutoff) and f4 (no evidence) cannot be cleared as pre-cutoff damage.
    assert.equal(res.status, 1, res.stderr);

    const inv = newest(tmp, 'mojibake-suspicion-').body;
    const man = newest(tmp, 'mojibake-candidates-').body;
    source = man.source;

    const byRid = Object.fromEntries(inv.rows.map((r) => [r.rid, r]));
    assert.deepEqual(Object.keys(byRid).sort(), ['a1', 'f1', 'f3', 'f4', 'f5', 'f6', 'f7', 'f8', 'z1']);
    assert.equal(byRid.f2, undefined, 'a clean row is never reported');
    assert.deepEqual(byRid.f1.flags, []);
    assert.ok(byRid.f3.flags.includes('post_cutoff_evidence'));
    assert.ok(byRid.f4.flags.includes('no_provenance'));
    assert.ok(byRid.f5.flags.includes('residual_mojibake'));
    assert.ok(byRid.f7.flags.includes('implausible_reversal'));
    assert.ok(byRid.a1.flags.includes('out_of_scope'));
    assert.ok(byRid.z1.flags.includes('out_of_scope'));

    // The manifest carries only what may be written: in scope, pre-cutoff, reversible. Flagged
    // rows still appear -- they are candidates a human must adjudicate, not silent omissions.
    assert.deepEqual(man.rows.map((r) => r.rid).sort(), ['f1', 'f5', 'f6', 'f7', 'f8']);
    assert.equal(man.rows.every((r) => r.table === 't_file'), true);
    assert.equal(man.repair_scope.includes('t_audit_log.log_message_c'), false);
  });

  test('the manifest is stamped with the cluster and database that produced it', () => {
    assert.ok(source.system_identifier && /^\d+$/.test(source.system_identifier));
    assert.equal(source.database, psql('select current_database()'));
  });

  test('output artifacts are owner-only', () => {
    assert.equal(fs.statSync(tmp).mode & 0o777, 0o700);
    for (const prefix of ['mojibake-suspicion-', 'mojibake-candidates-']) {
      const f = newest(tmp, prefix).path;
      assert.equal(fs.statSync(f).mode & 0o777, 0o600, `${f} must be 0600`);
    }
  });

  test('refuses an out-of-scope row even unflagged and even with --allow-flagged', () => {
    for (const row of [
      {
        schema: 'public', table: 't_audit_log', column: 'log_message_c', pk_column: 'log_id_c',
        rid: 'a1', before: KOERPER_BROKEN, after: 'Körper.pdf', flags: [],
      },
      {
        schema: 'public', table: 'zz_mojibake_test', column: 'name', pk_column: 'id',
        rid: 'z1', before: CJK_BROKEN, after: '日本.pdf', flags: [],
      },
    ]) {
      const file = writeManifest([row], tmp, source);
      for (const extra of [[], ['--allow-flagged']]) {
        const res = runTool(['--execute', '--manifest', file, '--out-dir', tmp, ...extra]);
        assert.equal(res.status, 2, res.stdout);
        assert.match(res.stderr, /may never write/);
      }
    }
    assert.equal(psql("select log_message_c from t_audit_log where log_id_c = 'a1'"), KOERPER_BROKEN);
    assert.equal(psql("select name from zz_mojibake_test where id = 'z1'"), CJK_BROKEN);
  });

  test('re-derives provenance and refuses a row the manifest wrongly declares clean', () => {
    // The manifest claims no flags for a post-cutoff row and for a row with no evidence at all.
    const post = writeManifest([fileRow('f3', AUSFUEHRUNG_BROKEN, 'Ausführung.pdf')], tmp, source);
    let res = runTool(['--execute', '--manifest', post, '--out-dir', tmp]);
    assert.equal(res.status, 2);
    assert.match(res.stderr, /not eligible against this database/);
    assert.match(res.stderr, /post_cutoff_evidence/);
    assert.equal(name('f3'), AUSFUEHRUNG_BROKEN);

    const none = writeManifest([fileRow('f4', MAASS_BROKEN, 'Maaß.pdf')], tmp, source);
    res = runTool(['--execute', '--manifest', none, '--out-dir', tmp]);
    assert.equal(res.status, 2);
    assert.match(res.stderr, /no_provenance/);
    assert.equal(name('f4'), MAASS_BROKEN);

    // Not even --allow-flagged opens these: the eligibility rule is re-derived from the database
    // and a provenance failure is not a flag a human can wave through.
    res = runTool(['--execute', '--manifest', post, '--out-dir', tmp, '--allow-flagged']);
    assert.equal(res.status, 2);
    assert.match(res.stderr, /not eligible against this database/);
    assert.equal(name('f3'), AUSFUEHRUNG_BROKEN);
  });

  test('an implausible reversal has no override at all', () => {
    // f7 is eligible -- in scope, pre-cutoff, cleanly reversible -- but its reversal is a control
    // character, so it is the one flag class --allow-flagged does not cover.
    const file = writeManifest([fileRow('f7', CONTROL_BROKEN, reverseMojibake(CONTROL_BROKEN))],
      tmp, source);
    for (const extra of [[], ['--allow-flagged']]) {
      const res = runTool(['--execute', '--manifest', file, '--out-dir', tmp, ...extra]);
      assert.equal(res.status, 2, res.stdout);
      assert.match(res.stderr, /implausible_reversal/);
      assert.equal(name('f7'), CONTROL_BROKEN);
    }
  });

  test('refuses a manifest minted against a different cluster or database', () => {
    const wrong = writeManifest([fileRow('f1', KOERPER_BROKEN, 'Körper.pdf')], tmp,
      { ...source, system_identifier: '1234567890123456789' });
    let res = runTool(['--execute', '--manifest', wrong, '--out-dir', tmp]);
    assert.equal(res.status, 2);
    assert.match(res.stderr, /Re-run the dry run against the target/);

    const otherDb = writeManifest([fileRow('f1', KOERPER_BROKEN, 'Körper.pdf')], tmp,
      { ...source, database: 'some_other_db' });
    res = runTool(['--execute', '--manifest', otherDb, '--out-dir', tmp]);
    assert.equal(res.status, 2);

    const unstamped = writeManifest([fileRow('f1', KOERPER_BROKEN, 'Körper.pdf')], tmp, undefined);
    res = runTool(['--execute', '--manifest', unstamped, '--out-dir', tmp]);
    assert.equal(res.status, 2);
    assert.match(res.stderr, /no source cluster identity/);
    assert.equal(name('f1'), KOERPER_BROKEN);
  });

  test('refuses a manifest whose after value was hand-edited', () => {
    const file = writeManifest([fileRow('f1', KOERPER_BROKEN, 'Korper.pdf')], tmp, source);
    const res = runTool(['--execute', '--manifest', file, '--out-dir', tmp]);
    assert.equal(res.status, 2);
    assert.match(res.stderr, /not the reversal/);
    assert.equal(name('f1'), KOERPER_BROKEN);
  });

  test('refuses a manifest naming a column this database does not have', () => {
    const file = writeManifest([{
      schema: 'public', table: 't_file', column: 'fil_name_c', pk_column: 'nope',
      rid: 'f1', before: KOERPER_BROKEN, after: 'Körper.pdf', flags: [],
    }], tmp, source);
    const res = runTool(['--execute', '--manifest', file, '--out-dir', tmp]);
    assert.equal(res.status, 2);
    assert.match(res.stderr, /does not have/);
  });

  test('a double-encoded row needs the explicit override', () => {
    const file = writeManifest([fileRow('f5', DOUBLE_BROKEN, KOERPER_BROKEN)], tmp, source);
    let res = runTool(['--execute', '--manifest', file, '--out-dir', tmp]);
    assert.equal(res.status, 2);
    assert.match(res.stderr, /residual_mojibake/);
    assert.equal(name('f5'), DOUBLE_BROKEN);

    res = runTool(['--execute', '--manifest', file, '--out-dir', tmp, '--allow-flagged']);
    assert.equal(res.status, 0, res.stderr);
    assert.equal(name('f5'), KOERPER_BROKEN, 'one pass peels exactly one layer');
  });

  test('repairs a manifest row, then no-ops on a second identical run', () => {
    const file = writeManifest([fileRow('f1', KOERPER_BROKEN, 'Körper.pdf')], tmp, source);

    const first = runTool(['--execute', '--manifest', file, '--out-dir', tmp]);
    assert.equal(first.status, 0, first.stderr);
    assert.match(first.stdout, /repaired 1, already-repaired no-op 0/);
    assert.equal(name('f1'), 'Körper.pdf');

    const second = runTool(['--execute', '--manifest', file, '--out-dir', tmp]);
    assert.equal(second.status, 0, second.stderr);
    assert.match(second.stdout, /repaired 0, already-repaired no-op 1/);
    assert.equal(name('f1'), 'Körper.pdf');

    const logs = fs.readdirSync(tmp).filter((n) => n.startsWith('mojibake-repair-')).sort();
    assert.ok(logs.length >= 2, 'each run writes its own change log');
    assert.match(fs.readFileSync(path.join(tmp, logs[0]), 'utf8'), /REPAIRED\tt_file\.fil_name_c/);
    assert.equal(fs.statSync(path.join(tmp, logs[0])).mode & 0o777, 0o600);
  });

  test('aborts before writing anything when a row drifted', () => {
    psql("update t_file set fil_name_c = 'renamed by a user.pdf' where fil_id_c = 'f6'");
    const file = writeManifest([
      fileRow('f1', KOERPER_BROKEN, 'Körper.pdf'),
      fileRow('f6', ENDASH_BROKEN, 'Rechnung – Rasenpflege.pdf'),
    ], tmp, source);

    const res = runTool(['--execute', '--manifest', file, '--out-dir', tmp]);
    assert.equal(res.status, 3);
    assert.match(res.stderr, /DRIFT/);
    assert.match(res.stderr, /f6/);
    assert.equal(name('f6'), 'renamed by a user.pdf');
  });

  test('rejects a manifest naming the same target twice', () => {
    // The re-damage shape: repair the row, then write it straight back to the damaged value. Each
    // row looks reasonable alone -- the second reads as an already-repaired no-op at pre-flight.
    const file = writeManifest([
      fileRow('f8', KOERPER_BROKEN, 'Körper.pdf'),
      fileRow('f8', 'Körper.pdf', KOERPER_BROKEN),
    ], tmp, source);
    const res = runTool(['--execute', '--manifest', file, '--out-dir', tmp]);
    assert.equal(res.status, 2);
    assert.match(res.stderr, /same target twice/);
    assert.equal(name('f8'), KOERPER_BROKEN);
  });

  test('rejects a row whose after value is not a repair of its before value', () => {
    const file = writeManifest([fileRow('f8', 'Körper.pdf', KOERPER_BROKEN)], tmp, source);
    const res = runTool(['--execute', '--manifest', file, '--out-dir', tmp]);
    assert.equal(res.status, 2);
    assert.match(res.stderr, /not the reversal/);
    assert.equal(name('f8'), KOERPER_BROKEN);
  });

  test('a password in the environment never reaches an artifact', () => {
    const res = runTool(['--out-dir', tmp], { PGPASSWORD: 'hunter2' });
    assert.ok([0, 1].includes(res.status), res.stderr);
    for (const prefix of ['mojibake-suspicion-', 'mojibake-candidates-']) {
      const body = fs.readFileSync(newest(tmp, prefix).path, 'utf8');
      assert.doesNotMatch(body, /hunter2/);
      assert.doesNotMatch(body, /password/i);
    }
    const src = newest(tmp, 'mojibake-candidates-').body.source;
    assert.deepEqual(Object.keys(src).sort(),
      ['database', 'dbname', 'host', 'port', 'server_user', 'system_identifier']);
  });

  test('the in-transaction guard refuses a stale plan on its own', () => {
    // Defence in depth: the pre-flight runs on other connections, at another time, so the
    // three-state check runs again inside the locking transaction. f2 is clean, the plan is stale.
    const res = runExecuteSql([planRow('f2', KOERPER_BROKEN, 'Körper.pdf')]);
    assert.notEqual(res.status, 0);
    assert.match(res.stderr, /DRIFT/);
    assert.equal(name('f2'), 'Bericht.pdf', 'the transaction rolled back');
  });

  test('the in-transaction guard refuses a plan minted against another cluster or database', () => {
    let res = runExecuteSql([planRow('f8', KOERPER_BROKEN, 'Körper.pdf')],
      { sysid: '1234567890123456789' });
    assert.notEqual(res.status, 0);
    assert.match(res.stderr, /IDENTITY: this cluster is/);
    assert.equal(name('f8'), KOERPER_BROKEN);

    res = runExecuteSql([planRow('f8', KOERPER_BROKEN, 'Körper.pdf')], { db: 'some_other_db' });
    assert.notEqual(res.status, 0);
    assert.match(res.stderr, /IDENTITY: this database is/);
    assert.equal(name('f8'), KOERPER_BROKEN);
  });

  test('the in-transaction guard re-checks provenance once the row is locked', () => {
    // f3's only evidence is post-cutoff, f4 has none at all. Neither may be written even though
    // this plan skips the pre-flight entirely.
    let res = runExecuteSql([planRow('f3', AUSFUEHRUNG_BROKEN, 'Ausführung.pdf')]);
    assert.notEqual(res.status, 0);
    assert.match(res.stderr, /PROVENANCE:.*is not before the cutoff/);
    assert.equal(name('f3'), AUSFUEHRUNG_BROKEN);

    res = runExecuteSql([planRow('f4', MAASS_BROKEN, 'Maaß.pdf')]);
    assert.notEqual(res.status, 0);
    assert.match(res.stderr, /PROVENANCE:.*no server-observed existence evidence/);
    assert.equal(name('f4'), MAASS_BROKEN);
  });

  test('the in-transaction end-state sweep refuses a plan that re-damages a row', () => {
    // Exactly the duplicate-target shape the manifest loader rejects, driven straight at the SQL:
    // row 0 repairs f1, row 1 writes the damage back. Both steps succeed individually; the end
    // state does not, and the whole transaction is lost.
    const res = runExecuteSql([
      planRow('f8', KOERPER_BROKEN, 'Körper.pdf'),
      planRow('f8', 'Körper.pdf', KOERPER_BROKEN),
    ]);
    assert.notEqual(res.status, 0);
    assert.match(res.stderr, /END STATE/);
    assert.equal(name('f8'), KOERPER_BROKEN, 'the transaction rolled back');
  });
});
