#!/usr/bin/env node
// One-time data repair for UTF-8-as-Latin-1 mojibake in stored user text (#207, follow-through on #143).
//
// Until 2026-07-19 the multipart upload path stored filenames as Jersey handed them over: browsers
// transmit the Content-Disposition filename as UTF-8 bytes, Jersey decodes that header as ISO-8859-1,
// so `Körper.pdf` was persisted as `KÃ¶rper.pdf`. FileResource.repairMultipartFilename fixed the
// INGEST path; rows written before that deploy still carry the damage. This tool finds and reverses
// it. It is not a migration: the damage is historical, the row set is small, and every write is
// gated on a human-approved manifest.
//
// Reversal is the same transform the runtime fix uses, deliberately: encode the stored string to
// ISO-8859-1 (one byte per char) and decode those bytes as UTF-8 with a STRICT decoder. A malformed
// sequence means the bytes were never UTF-8 and the value is left alone. The classification limit
// documented on repairMultipartFilename applies here too: a genuine Latin-1 name whose bytes happen
// to form valid UTF-8 is indistinguishable from mojibake. That is why nothing is repaired without an
// explicit manifest.
//
// PROVENANCE is existence evidence, never insertion time. DOC_CREATEDATE_D is client-suppliable
// (DocumentResource lets the caller pass create_date; the EML importer sets it from the message
// date), so it cannot gate a repair. What the server controls is the audit log: AuditLogDao.create
// stamps LOG_CREATEDATE_D with its own clock, so the EARLIEST audit event of ANY type for an entity
// proves the entity already existed at that instant. DocumentDao.restore also emits a Document/CREATE
// event, so "the" insertion event cannot be identified -- the minimum over all events can, and it is
// the conservative choice. FIL_CREATEDATE_D and LOG_CREATEDATE_D are server-set (FileDao.create,
// AuditLogDao.create) and count as evidence of the same kind. An entity with no evidence at all is
// flagged and held, never repaired.
//
// WHAT THIS TOOL MAY WRITE is a hardcoded, non-overridable allowlist (REPAIR_SCOPE): the two columns
// the traced ingest paths can fill with a user-supplied name. Everything else in the database --
// including T_AUDIT_LOG, whose messages carry copies of the same damaged names -- is swept and
// reported but is UNWRITABLE by this tool under any flag. The audit log is history: it already, by
// design, disagrees with rows that were later renamed by hand.
//
// A MANIFEST IS NOT TRUSTED. It selects rows; it never authorises them. Nothing in the file decides
// anything: the manifest cannot name the same target twice, and every value, reversal, scope check
// and provenance check is re-derived from the database.
//
// WHERE THE CHECKS THAT GATE WRITES LIVE. Each psql invocation is its own connection, so a check run
// before the repair proves nothing about the moment of the write. The pre-flight (out of transaction)
// exists to fail fast with a readable message; the checks that actually gate the writes all run
// INSIDE the single locking transaction in EXECUTE_SQL, on the connection holding the row locks:
//   - the cluster and database identity the manifest was minted against,
//   - per row, after SELECT ... FOR UPDATE: the exact stored value (three-state), and for any row
//     about to be written, its server-observed pre-cutoff existence evidence,
//   - after the whole plan is applied, an END STATE sweep asserting every target now holds its
//     repaired value -- so no combination of plan rows can leave a target damaged.
// The value transform itself is not re-run in SQL: the locked value is compared byte-for-byte with
// the pre-flight's `before`, and `after` was derived from exactly that string, so an equal value
// means an equal reversal.
//
// CONNECTION PARAMETERS have exactly one ingress: resolveConnection(). Every flag and every libpq
// environment variable psql would consume is resolved and validated there, and the psql environment
// is rebuilt from scratch so that nothing else can influence the connection. PGPASSWORD/PGPASSFILE
// are the only credential channels; they are passed through untouched, never read, never persisted.
//
// Modes:
//   (default) dry run  -- read-only. Emits TWO layers:
//                         (i)  suspicion inventory: every pattern+round-trip hit in every character
//                              column of the database, NO provenance cutoff and NO scope filter
//                              applied. Post-cutoff hits are meant to be visible here -- they would
//                              mean ingest is still producing damage.
//                         (ii) candidate manifest: the subset passing every eligibility rule,
//                              which includes being inside REPAIR_SCOPE.
//   --execute --manifest F -- transactional repair of exactly the rows in F, three-state per row.
//
// MANDATORY POST-STEP after a production --execute: reconcile the Lucene search index. The
// repairable columns (document titles, file names) are both copied into the index, and direct SQL
// writes emit no reindex events, so search keeps serving the pre-repair tokens. Restarting alone
// does NOT reconcile a populated index. Either POST /api/app/batch/reindex as an admin, or stop
// the app, move the lucene data directory aside, and start it -- an empty index over a populated
// database triggers the automatic boot rebuild.

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

// The #143 deploy. Anything whose earliest server-side evidence predates this was stored by the
// broken ingest path; anything after it must not be silently repaired, it must be investigated.
const DEFAULT_CUTOFF = '2026-07-19T00:00:00.000';

// The only columns this tool may ever write. Hardcoded on purpose: the multipart filename
// (FileResource.resolveUploadFilename -> FileUtil.createFile -> FIL_NAME_C) and the document title
// (DocumentResource create/update, and the importer, which derives the title from the filename).
// There is no flag that widens this.
const REPAIR_SCOPE = ['t_file.fil_name_c', 't_document.doc_title_c'];

// Server-set timestamps, verified in the DAOs, usable as existence evidence on their own.
const SERVER_SET_TIMESTAMP = {
  t_file: 'fil_createdate_d',      // FileDao.create
  t_audit_log: 'log_createdate_d', // AuditLogDao.create
};

// Values from these columns are never printed: a hit here would be reported, not disclosed.
const SECRET_COLUMN = /pass|token|secret|salt|hash|private/i;

// A stored value longer than this is reported as a hit but not analysed or repaired: reading whole
// extracted-document bodies into memory to test a filename hypothesis is not worth the blast radius.
const MAX_VALUE_CHARS = 4096;

// UTF-8 lead byte (0xC2-0xF4) followed by a continuation byte (0x80-0xBF), read as Latin-1 chars.
const SQL_MOJIBAKE_PATTERN = String.raw`U&'[\00C2-\00F4][\0080-\00BF]'`;

// Every read the dry run and the executor's pre-flight perform is structurally incapable of writing.
const READ_ONLY = 'set session characteristics as transaction read only;\n';

// The output artifacts list real document filenames, so they are owner-only.
const DIR_MODE = 0o700;
const FILE_MODE = 0o600;

const isLead = (c) => c >= 0xc2 && c <= 0xf4;
const isCont = (c) => c >= 0x80 && c <= 0xbf;

/** True when the value contains a sequence that LOOKS like UTF-8 bytes read as Latin-1. */
export function hasMojibakePattern(value) {
  for (let i = 0; i + 1 < value.length; i++) {
    if (isLead(value.charCodeAt(i)) && isCont(value.charCodeAt(i + 1))) return true;
  }
  return false;
}

/**
 * Encodes to ISO-8859-1 and decodes as UTF-8 with a strict decoder, mirroring
 * FileResource.repairMultipartFilename. Returns null when the value cannot be a mojibake reading:
 * a code point above U+00FF proves the string was already decoded to Unicode, and a malformed
 * sequence proves the bytes were never UTF-8.
 */
export function reverseMojibake(value) {
  const bytes = Buffer.alloc(value.length);
  for (let i = 0; i < value.length; i++) {
    const c = value.charCodeAt(i);
    if (c > 0xff) return null;
    bytes[i] = c;
  }
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  } catch {
    return null;
  }
}

/** A reversal that yields control characters or U+FFFD is not a plausible filename or title. */
function isImplausible(text) {
  for (const ch of text) {
    const c = ch.codePointAt(0);
    if (c < 0x20 || (c >= 0x7f && c <= 0x9f) || c === 0xfffd) return true;
  }
  return false;
}

/**
 * Applies the value-level half of the eligibility rules: pattern present, strict round-trip
 * succeeds, and the reversal actually changes the value.
 */
export function classifyValue(value) {
  if (typeof value !== 'string' || !hasMojibakePattern(value)) return { suspect: false };
  const reversed = reverseMojibake(value);
  if (reversed === null || reversed === value) return { suspect: false };

  const flags = [];
  if (isImplausible(reversed)) flags.push('implausible_reversal');
  // A reversal that is itself a candidate was double-encoded. One pass peels one layer; a later
  // dry run would list the result again as a new candidate with a new before/after.
  if (hasMojibakePattern(reversed)) {
    const twice = reverseMojibake(reversed);
    if (twice !== null && twice !== reversed) flags.push('residual_mojibake');
  }
  return { suspect: true, reversed, flags };
}

/**
 * The single eligibility rule, shared by the dry run and by the executor's re-derivation so the two
 * can never drift apart. Returns null when the value is not a hit at all.
 */
export function assess({ table, column, value, length, firstEvidence, cutoff }) {
  const flags = [];
  const oversize = length > MAX_VALUE_CHARS;
  const secret = SECRET_COLUMN.test(column);
  const analysis = oversize ? { suspect: false } : classifyValue(value);
  if (!oversize && !analysis.suspect) return null;
  if (oversize) flags.push('oversize_not_analysed');
  if (secret) flags.push('sensitive_column');
  flags.push(...(analysis.flags || []));

  const inScope = REPAIR_SCOPE.includes(`${table}.${column}`);
  if (!inScope) flags.push('out_of_scope');
  if (!firstEvidence) flags.push('no_provenance');
  else if (firstEvidence >= cutoff) flags.push('post_cutoff_evidence');

  return {
    flags,
    reversed: oversize || secret ? null : analysis.reversed,
    // A redacted or unread value can never be proposed for a write: its before/after would not be
    // the real stored text. Out-of-scope hits stay in the inventory and never reach the manifest.
    eligible: Boolean(!oversize && !secret && analysis.suspect && inScope
      && firstEvidence && firstEvidence < cutoff),
  };
}

/** Flags a human may knowingly override with --allow-flagged. Nothing else is overridable. */
const OVERRIDABLE_FLAGS = new Set(['residual_mojibake']);

function fail(message, code = 2) {
  console.error(`error: ${message}`);
  process.exit(code);
}

// ---------------------------------------------------------------------------------------------
// Connection parameters: one ingress, one validation, one source of what gets persisted.
// ---------------------------------------------------------------------------------------------

// A plain database or role name. Anything richer -- a URI, a conninfo key=value string -- can carry
// a password, and psql expands exactly that when it is handed as a dbname.
const PLAIN_NAME = /^[A-Za-z0-9_$-]{1,63}$/;
const PLAIN_HOST = /^[A-Za-z0-9_.:/-]{1,255}$/;
const PLAIN_PORT = /^[0-9]{1,5}$/;

// The only environment variables allowed to reach psql. They are the sanctioned credential
// channels: the tool never reads them, never logs them and never persists them.
const CREDENTIAL_ENV = ['PGPASSWORD', 'PGPASSFILE'];

// These name a connection service, whose file can supply host, port, user, dbname AND password
// without any of them passing through this tool's validation. Refused rather than silently ignored,
// so an operator relying on one is told instead of quietly connected somewhere else.
const REFUSED_ENV = ['PGSERVICE', 'PGSERVICEFILE', 'PGHOSTADDR'];

const CONNECTION_PARAMS = [
  { key: 'host', flag: '--host', env: 'PGHOST', pattern: PLAIN_HOST, what: 'a plain host or socket path' },
  { key: 'port', flag: '--port', env: 'PGPORT', pattern: PLAIN_PORT, what: 'a port number' },
  { key: 'user', flag: '--user', env: 'PGUSER', pattern: PLAIN_NAME, what: 'a plain role name' },
  { key: 'dbname', flag: '--dbname', env: 'PGDATABASE', pattern: PLAIN_NAME, what: 'a plain database name' },
];

function checkNoCredentials(what, value) {
  if (/:\/\//.test(value) || /(^|[\s&?;])(password|passfile)\s*=/i.test(value) || value.includes('@')) {
    fail(`${what} looks like a connection URI or conninfo string. This tool refuses those so a `
      + 'password cannot reach an output artifact: pass a plain name and supply the password '
      + 'through PGPASSWORD, PGPASSFILE or ~/.pgpass.');
  }
}

/**
 * The single place a connection parameter can enter. Every flag and every environment fallback is
 * resolved to one effective value and validated here whatever its source, including a blank flag
 * that would otherwise fall through to an unvalidated environment variable. The returned object is
 * the only thing psqlArgs and the persisted artifacts ever see.
 */
export function resolveConnection(flags, env = process.env) {
  for (const name of REFUSED_ENV) {
    const value = env[name];
    if (value !== undefined && value.trim() !== '') {
      fail(`${name} is set. A connection service can supply the host, user, database and password `
        + 'without passing through this tool\'s validation, so the tool refuses to run with it. '
        + 'Pass --host/--port/--user/--dbname explicitly.');
    }
  }

  const resolved = {};
  for (const p of CONNECTION_PARAMS) {
    const fromFlag = flags[p.key];
    const value = fromFlag !== undefined ? fromFlag : env[p.env];
    const source = fromFlag !== undefined ? p.flag : p.env;
    if (value === undefined) {
      resolved[p.key] = null; // unset: psql's own default applies, and the server tells us what it was
      continue;
    }
    if (value.trim() === '') {
      fail(`${source} is empty. Leave it unset to take the libpq default rather than passing a `
        + 'blank value: a blank one would silently hand the choice to an unvalidated source.');
    }
    checkNoCredentials(source, value);
    if (!p.pattern.test(value)) fail(`${source} must be ${p.what}, got ${JSON.stringify(value)}`);
    resolved[p.key] = value;
  }
  return resolved;
}

/**
 * psql runs with a rebuilt environment: every PG* variable is dropped except the credential
 * channels, so no variable this tool has not resolved can redirect the connection or inject a
 * setting. Anything new libpq gains in a future release is excluded by construction.
 */
function psqlEnv() {
  const env = {};
  for (const [k, v] of Object.entries(process.env)) {
    if (k.startsWith('PG') && !CREDENTIAL_ENV.includes(k)) continue;
    env[k] = v;
  }
  env.PGCLIENTENCODING = 'UTF8';
  return env;
}

function parseArgs(argv) {
  const flags = {};
  const opts = {
    execute: false, manifest: null, allowFlagged: false, outDir: null, cutoff: DEFAULT_CUTOFF,
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => {
      if (i + 1 >= argv.length) fail(`${a} needs a value`);
      return argv[++i];
    };
    switch (a) {
      case '--execute': opts.execute = true; break;
      case '--dry-run': opts.execute = false; break;
      case '--manifest': opts.manifest = next(); break;
      case '--allow-flagged': opts.allowFlagged = true; break;
      case '--out-dir': opts.outDir = next(); break;
      case '--cutoff': {
        const v = next();
        if (!/^\d{4}-\d{2}-\d{2}$/.test(v)) fail('--cutoff must be YYYY-MM-DD');
        opts.cutoff = `${v}T00:00:00.000`;
        break;
      }
      case '--host': flags.host = next(); break;
      case '--port': flags.port = next(); break;
      case '--user': flags.user = next(); break;
      case '--dbname': flags.dbname = next(); break;
      case '--help': case '-h': printUsage(); process.exit(0); break;
      default: fail(`unknown argument: ${a}`);
    }
  }
  if (opts.execute && !opts.manifest) fail('--execute requires --manifest <file>');
  if (!opts.execute && opts.manifest) fail('--manifest is only meaningful with --execute');
  opts.conn = resolveConnection(flags);
  return opts;
}

function printUsage() {
  console.log(`repair-mojibake.mjs -- find and reverse UTF-8-as-Latin-1 mojibake in stored user text

  dry run (default, read-only, writes no database row):
    node scripts/repair-mojibake.mjs [--host H] [--port P] [--user U] [--dbname D]
                                     [--out-dir DIR] [--cutoff YYYY-MM-DD]

  repair exactly the rows of an approved manifest:
    node scripts/repair-mojibake.mjs --execute --manifest FILE [--allow-flagged] [--out-dir DIR]

  MANDATORY after a production --execute: reconcile the Lucene search index. Titles and file
  names are indexed, and direct SQL writes emit no reindex events, so search keeps serving the
  pre-repair tokens; a plain restart does not fix a populated index. Either POST
  /api/app/batch/reindex as an admin, or stop the app, move the lucene data directory aside, and
  start it (an empty index over a populated database triggers the automatic boot rebuild).

Connection parameters come from those flags or from PGHOST/PGPORT/PGUSER/PGDATABASE, are validated
whatever their source, and are the only ones psql sees: every other PG* variable is dropped, and
PGSERVICE, PGSERVICEFILE and PGHOSTADDR are refused because a service file can supply a target and a
password this tool cannot check. Supply the password through PGPASSWORD, PGPASSFILE or ~/.pgpass --
this tool never reads, prints or persists it.

The dry run writes two owner-only files (directory 0700, files 0600) to the output directory
(default: a teedy-mojibake directory under the system temporary directory): a suspicion inventory
(every hit, no cutoff and no scope filter applied) and a candidate manifest (the eligible subset).

Only these columns are ever writable, whatever a manifest says: ${REPAIR_SCOPE.join(', ')}.
A manifest may not name the same target twice. At execution every row is re-derived from the
database, and the checks that gate the writes -- cluster and database identity, the locked stored
value, pre-cutoff existence evidence, and an end-state sweep over every target -- all run inside the
single transaction that holds the row locks. A manifest is bound to the cluster and database that
produced it, so repairing a system requires a dry run against that same system. --allow-flagged
permits exactly one flag, residual_mojibake (a double-encoded value, where one pass peels one
layer); provenance, scope and plausibility failures have no override.

Exit codes: 0 success; 1 dry run found damage it cannot clear as pre-cutoff (post-cutoff evidence, or
no evidence at all); 2 usage, validation or manifest refusal; 3 repair aborted and rolled back.`);
}

function psqlArgs(conn) {
  const args = ['-X', '-q', '-A', '-t', '-P', 'pager=off', '-v', 'ON_ERROR_STOP=1'];
  if (conn.host) args.push('-h', conn.host);
  if (conn.port) args.push('-p', String(conn.port));
  if (conn.user) args.push('-U', conn.user);
  if (conn.dbname) args.push('-d', conn.dbname);
  return args;
}

function runPsql(conn, { file, vars = {} }) {
  const args = psqlArgs(conn);
  for (const [k, v] of Object.entries(vars)) args.push('-v', `${k}=${v}`);
  args.push('-f', file);
  const res = spawnSync('psql', args, {
    encoding: 'utf8',
    maxBuffer: 256 * 1024 * 1024,
    env: psqlEnv(),
  });
  if (res.error) fail(`could not run psql: ${res.error.message}`);
  return res;
}

/**
 * json_agg pretty-prints across several lines, so the payload is everything from the first bracket
 * to the end of the output. A raw newline can only ever be one of its separators: inside a JSON
 * string a newline is escaped.
 */
function jsonPayload(stdout) {
  const start = stdout.search(/[[{]/);
  if (start < 0) throw new Error('psql returned no JSON payload');
  return JSON.parse(stdout.slice(start).trim());
}

function withTempSql(sql, fn) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'mojibake-sql-'));
  const file = path.join(dir, 'query.sql');
  try {
    fs.writeFileSync(file, sql, 'utf8');
    return fn(file);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
}

function query(conn, sql) {
  const res = withTempSql(sql, (file) => runPsql(conn, { file }));
  if (res.status !== 0) fail(`psql failed (exit ${res.status}):\n${res.stderr.trim()}`);
  return jsonPayload(res.stdout);
}

/** Identifiers come from the catalog; anything that is not a plain lower-case name is refused. */
function qi(name) {
  if (!/^[a-z_][a-z0-9_$]*$/.test(name)) fail(`refusing to quote unexpected identifier: ${name}`);
  return `"${name}"`;
}

function ql(value) {
  return `'${String(value).replace(/'/g, "''")}'`;
}

const CATALOG_SQL = `${READ_ONLY}select coalesce(json_agg(x), '[]') from (
  select c.table_schema as sch, c.table_name as tbl, c.column_name as col,
         (select k.column_name
            from information_schema.table_constraints tc
            join information_schema.key_column_usage k
              on k.constraint_name = tc.constraint_name and k.table_schema = tc.table_schema
           where tc.constraint_type = 'PRIMARY KEY'
             and tc.table_schema = c.table_schema and tc.table_name = c.table_name
           limit 1) as pkcol,
         (select count(*)
            from information_schema.table_constraints tc
            join information_schema.key_column_usage k
              on k.constraint_name = tc.constraint_name and k.table_schema = tc.table_schema
           where tc.constraint_type = 'PRIMARY KEY'
             and tc.table_schema = c.table_schema and tc.table_name = c.table_name) as pkcols
    from information_schema.columns c
    join information_schema.tables t
      on t.table_schema = c.table_schema and t.table_name = c.table_name and t.table_type = 'BASE TABLE'
   where c.table_schema = 'public'
     and c.data_type in ('character varying', 'text', 'character')
   order by c.table_name, c.column_name) x;`;

/**
 * Identity of the cluster and database, read out of transaction for a fast pre-flight message. The
 * binding that actually gates the repair is re-checked inside the write transaction.
 */
function clusterIdentity(conn) {
  const rows = query(conn, `${READ_ONLY}select coalesce(json_agg(x), '[]') from (
  select (select system_identifier::text from pg_control_system()) as system_identifier,
         current_database() as database, current_user as usename) x;`);
  const id = rows[0];
  if (!id || !id.system_identifier || !id.database) fail('could not read the cluster identity');
  return id;
}

function sweep(conn, columns) {
  if (columns.length === 0) return [];
  const parts = columns.map((c) => `select ${ql(c.sch)}::text as sch, ${ql(c.tbl)}::text as tbl,`
    + ` ${ql(c.col)}::text as col, ${ql(c.pkcol)}::text as pkcol,`
    + ` ${qi(c.pkcol)}::text as rid, length(${qi(c.col)}) as len,`
    + ` left(${qi(c.col)}, ${MAX_VALUE_CHARS})::text as val`
    + ` from ${qi(c.sch)}.${qi(c.tbl)} where ${qi(c.col)} ~ ${SQL_MOJIBAKE_PATTERN}`);
  return query(conn, `${READ_ONLY}select coalesce(json_agg(x), '[]') from (\n${parts.join('\nunion all\n')}\n) x;`);
}

/** Current stored values for a set of rows, read outside the repair transaction (pre-flight only). */
function currentValues(conn, rows) {
  if (rows.length === 0) return new Map();
  const groups = new Map();
  for (const r of rows) {
    const key = `${r.sch}.${r.tbl}.${r.col}.${r.pkcol}`;
    if (!groups.has(key)) groups.set(key, { ...r, rids: [] });
    groups.get(key).rids.push(r.rid);
  }
  const parts = [...groups.values()].map((g) => `select ${ql(g.tbl)}::text as tbl,`
    + ` ${ql(g.col)}::text as col, ${qi(g.pkcol)}::text as rid, length(${qi(g.col)}) as len,`
    + ` left(${qi(g.col)}, ${MAX_VALUE_CHARS})::text as val`
    + ` from ${qi(g.sch)}.${qi(g.tbl)} where ${qi(g.pkcol)} in (${g.rids.map(ql).join(',')})`);
  const out = new Map();
  for (const r of query(conn, `${READ_ONLY}select coalesce(json_agg(x), '[]') from (\n${parts.join('\nunion all\n')}\n) x;`)) {
    out.set(`${r.tbl}:${r.rid}`, r);
  }
  return out;
}

function auditEvidence(conn, rids, catalog) {
  // Absent in a database that is not a Teedy schema: no audit table means no evidence, which the
  // caller already treats as "hold this row".
  if (rids.length === 0 || !catalog.some((c) => c.tbl === 't_audit_log')) return [];
  const list = rids.map(ql).join(',');
  return query(conn, `${READ_ONLY}select coalesce(json_agg(x), '[]') from (
  select log_identity_c as rid,
         to_char(min(log_createdate_d), 'YYYY-MM-DD"T"HH24:MI:SS.MS') as first_evidence,
         to_char(max(log_createdate_d), 'YYYY-MM-DD"T"HH24:MI:SS.MS') as last_evidence,
         count(*)::int as events
    from public.t_audit_log where log_identity_c in (${list}) group by 1) x;`);
}

/**
 * Which of the declared server-set timestamp columns this database actually has. The column
 * catalog only covers character columns, and the map is keyed by Teedy table names, so a database
 * that is not a Teedy schema must be able to answer "none" instead of erroring.
 */
function serverSetColumns(conn) {
  const pairs = Object.entries(SERVER_SET_TIMESTAMP)
    .map(([t, c]) => `(${ql(t)}, ${ql(c)})`).join(', ');
  const rows = query(conn, `${READ_ONLY}select coalesce(json_agg(x), '[]') from (
  select table_name as tbl, column_name as col from information_schema.columns
   where table_schema = 'public' and (table_name, column_name) in (${pairs})) x;`);
  return new Set(rows.map((r) => `${r.tbl}.${r.col}`));
}

function serverSetEvidence(conn, table, pkcol, rids, present) {
  const col = SERVER_SET_TIMESTAMP[table];
  if (!col || rids.length === 0 || !present.has(`${table}.${col}`)) return [];
  const list = rids.map(ql).join(',');
  return query(conn, `${READ_ONLY}select coalesce(json_agg(x), '[]') from (
  select ${qi(pkcol)}::text as rid,
         to_char(${qi(col)}, 'YYYY-MM-DD"T"HH24:MI:SS.MS') as ts
    from public.${qi(table)} where ${qi(pkcol)} in (${list})) x;`);
}

/**
 * Server-observed existence evidence per row, keyed table:rid, for the dry run and the pre-flight.
 * The executor re-derives the same thing in SQL once the rows are locked.
 */
function gatherEvidence(conn, rows, catalog) {
  const out = new Map();
  if (rows.length === 0) return out;
  const audit = new Map();
  for (const a of auditEvidence(conn, [...new Set(rows.map((r) => r.rid))], catalog)) {
    audit.set(a.rid, a);
  }
  const present = serverSetColumns(conn);
  const own = new Map();
  for (const table of new Set(rows.map((r) => r.tbl))) {
    const tableRows = rows.filter((r) => r.tbl === table);
    for (const e of serverSetEvidence(conn, table, tableRows[0].pkcol,
      tableRows.map((r) => r.rid), present)) {
      if (e.ts) own.set(`${table}:${e.rid}`, e.ts);
    }
  }
  for (const r of rows) {
    const a = audit.get(r.rid);
    const o = own.get(`${r.tbl}:${r.rid}`);
    const sources = [];
    if (a) sources.push(`audit_log(${a.events})`);
    if (o) sources.push(`${r.tbl}.${SERVER_SET_TIMESTAMP[r.tbl]}`);
    const dates = [a?.first_evidence, o].filter(Boolean).sort();
    out.set(`${r.tbl}:${r.rid}`, {
      first: dates[0] || null,
      last: [a?.last_evidence, o].filter(Boolean).sort().pop() || null,
      sources,
    });
  }
  return out;
}

function timestampSlug() {
  return new Date().toISOString().replace(/[-:]/g, '').replace('.', '');
}

function resolveOutDir(opts) {
  const dir = opts.outDir || path.join(os.tmpdir(), 'teedy-mojibake');
  fs.mkdirSync(dir, { recursive: true, mode: DIR_MODE });
  fs.chmodSync(dir, DIR_MODE); // mkdir honours the umask, and skips a directory that already exists
  return dir;
}

/** A change log is evidence: never let a second run in the same instant overwrite the first. */
function uniquePath(dir, prefix, suffix) {
  const base = path.join(dir, `${prefix}${timestampSlug()}`);
  let candidate = `${base}${suffix}`;
  for (let n = 2; fs.existsSync(candidate); n++) candidate = `${base}-${n}${suffix}`;
  return candidate;
}

/** Artifacts carry real document filenames, so they are written owner-only, umask notwithstanding. */
function writePrivate(file, contents) {
  fs.writeFileSync(file, contents, { mode: FILE_MODE });
  fs.chmodSync(file, FILE_MODE);
}

function preview(column, value) {
  if (value === null || value === undefined) return null;
  if (SECRET_COLUMN.test(column)) return '<redacted: sensitive column>';
  return value;
}

/**
 * What goes into an artifact: the validated connection parameters from the single resolve step,
 * plus what the server itself reported. No other string describing the connection is persisted.
 */
function describeSource(conn, identity) {
  return {
    host: conn.host || 'unset',
    port: conn.port || 'unset',
    dbname: conn.dbname || 'unset',
    system_identifier: identity.system_identifier,
    database: identity.database,
    server_user: identity.usename,
  };
}

function dryRun(opts) {
  const catalog = query(opts.conn, CATALOG_SQL);
  const identity = clusterIdentity(opts.conn);
  const skipped = catalog.filter((c) => c.pkcols !== 1);
  const columns = catalog.filter((c) => c.pkcols === 1 && c.pkcol);
  const hits = sweep(opts.conn, columns);
  const evidence = gatherEvidence(opts.conn, hits, catalog);

  const inventory = [];
  for (const h of hits) {
    const ev = evidence.get(`${h.tbl}:${h.rid}`) || { first: null, last: null, sources: [] };
    const a = assess({
      table: h.tbl, column: h.col, value: h.val, length: h.len,
      firstEvidence: ev.first, cutoff: opts.cutoff,
    });
    if (!a) continue; // pattern hit that fails the strict round-trip
    const oversize = h.len > MAX_VALUE_CHARS;
    inventory.push({
      schema: h.sch, table: h.tbl, column: h.col, pk_column: h.pkcol, rid: h.rid,
      length: h.len,
      before: oversize ? `${preview(h.col, h.val)} [truncated at ${MAX_VALUE_CHARS} of ${h.len} chars]`
        : preview(h.col, h.val),
      after: preview(h.col, a.reversed),
      first_evidence: ev.first, last_evidence: ev.last, evidence: ev.sources,
      in_scope: REPAIR_SCOPE.includes(`${h.tbl}.${h.col}`),
      eligible: a.eligible,
      flags: a.flags,
    });
  }

  const candidates = inventory.filter((r) => r.eligible);
  const outDir = resolveOutDir(opts);
  const meta = {
    generated_at: new Date().toISOString(),
    cutoff: opts.cutoff,
    repair_scope: REPAIR_SCOPE,
    source: describeSource(opts.conn, identity),
    columns_swept: columns.length,
    tables_skipped_no_single_column_pk: skipped.map((c) => `${c.tbl}`),
  };
  const invFile = uniquePath(outDir, 'mojibake-suspicion-', '.json');
  const manFile = uniquePath(outDir, 'mojibake-candidates-', '.json');
  writePrivate(invFile, `${JSON.stringify({ ...meta, layer: 'suspicion-inventory', rows: inventory }, null, 2)}\n`);
  writePrivate(manFile, `${JSON.stringify({ ...meta, layer: 'candidate-manifest', rows: candidates }, null, 2)}\n`);

  console.log(`swept ${columns.length} character columns; ${skipped.length} column(s) skipped for lack of a single-column primary key`);
  console.log(`\n=== layer 1: suspicion inventory (${inventory.length} rows, NO cutoff or scope filter) ===`);
  for (const r of inventory) {
    console.log(`${r.table}.${r.column} ${r.rid}`);
    console.log(`   before : ${JSON.stringify(r.before)}`);
    console.log(`   after  : ${JSON.stringify(r.after)}`);
    console.log(`   first evidence ${r.first_evidence || '(none)'} [${r.evidence.join(' ') || 'none'}]  eligible=${r.eligible}  flags=[${r.flags.join(' ')}]`);
  }
  const unclear = inventory.filter((r) => r.flags.includes('post_cutoff_evidence')
    || r.flags.includes('no_provenance'));
  if (unclear.length) {
    console.log(`\n!! ${unclear.length} row(s) could not be cleared as existing before ${opts.cutoff}.`);
    console.log('!! The ingest fix should make new damage impossible: investigate before repairing anything.');
  }
  console.log(`\n=== layer 2: candidate manifest (${candidates.length} eligible rows) ===`);
  for (const r of candidates) {
    console.log(`${r.table}.${r.column} ${r.rid}  ${r.first_evidence}  flags=[${r.flags.join(' ')}]`);
  }
  console.log(`\ninventory : ${invFile}`);
  console.log(`manifest  : ${manFile}`);
  console.log(`unflagged candidates: ${candidates.filter((r) => r.flags.length === 0).length} of ${candidates.length}`);
  return unclear.length === 0 ? 0 : 1;
}

/**
 * The whole repair, as one transaction on one connection. Everything that gates a write is in here:
 * the identity binding, the locked three-state check, the provenance re-check for each row about to
 * change, and the end-state sweep. The pre-flight outside cannot substitute for any of it -- it runs
 * on other connections, at another time.
 */
export const EXECUTE_SQL = `begin;
set local lock_timeout = '10s';
set local statement_timeout = '300s';
create temp table _mojibake_params (sysid text, db text, cutoff timestamp) on commit drop;
insert into _mojibake_params values (:'sysid', :'db', :'cutoff'::timestamp);
create temp table _mojibake_plan (
  ord int, sch text, tbl text, col text, pkcol text, rid text,
  tscol text, val_before text, val_after text
) on commit drop;
insert into _mojibake_plan
select * from json_to_recordset(:'plan'::json)
  as x(ord int, sch text, tbl text, col text, pkcol text, rid text,
       tscol text, val_before text, val_after text);
create temp table _mojibake_result (rid text, sch text, tbl text, col text, outcome text) on commit drop;
do $mojibake$
declare
  p record; r record; cur text; hits bigint;
  ev_audit timestamp; ev_own timestamp; first_ev timestamp; has_audit boolean;
begin
  select * into p from _mojibake_params;

  -- Identity, on the connection that is about to take the locks and write.
  if (select system_identifier::text from pg_control_system()) is distinct from p.sysid then
    raise exception 'IDENTITY: this cluster is %, the manifest was minted against %',
      (select system_identifier::text from pg_control_system()), p.sysid;
  end if;
  if current_database() is distinct from p.db then
    raise exception 'IDENTITY: this database is %, the manifest was minted against %',
      current_database(), p.db;
  end if;
  has_audit := to_regclass('public.t_audit_log') is not null;

  for r in select * from _mojibake_plan order by ord loop
    execute format('select %I from %I.%I where %I = $1 for update', r.col, r.sch, r.tbl, r.pkcol)
      into cur using r.rid;
    -- EXECUTE deliberately leaves FOUND alone (it is only set by the static statements), so the
    -- row-missing test has to read ROW_COUNT. Using FOUND here silently aborts every run.
    get diagnostics hits = row_count;
    if hits = 0 then
      raise exception 'DRIFT: %.%.% row % is gone', r.sch, r.tbl, r.col, r.rid;
    end if;

    if cur is not distinct from r.val_before then
      -- The row is locked and is about to change: prove its provenance here, not earlier and not
      -- on another connection.
      ev_audit := null;
      ev_own := null;
      if has_audit then
        select min(log_createdate_d) into ev_audit
          from public.t_audit_log where log_identity_c = r.rid;
      end if;
      if r.tscol is not null then
        execute format('select %I from %I.%I where %I = $1', r.tscol, r.sch, r.tbl, r.pkcol)
          into ev_own using r.rid;
      end if;
      first_ev := least(ev_audit, ev_own);
      if first_ev is null then
        raise exception 'PROVENANCE: %.% row % has no server-observed existence evidence',
          r.tbl, r.col, r.rid;
      end if;
      if first_ev >= p.cutoff then
        raise exception 'PROVENANCE: %.% row % evidence % is not before the cutoff %',
          r.tbl, r.col, r.rid, first_ev, p.cutoff;
      end if;

      execute format('update %I.%I set %I = $1 where %I = $2', r.sch, r.tbl, r.col, r.pkcol)
        using r.val_after, r.rid;
      insert into _mojibake_result values (r.rid, r.sch, r.tbl, r.col, 'REPAIRED');
    elsif cur is not distinct from r.val_after then
      insert into _mojibake_result values (r.rid, r.sch, r.tbl, r.col, 'NOOP');
    else
      raise exception 'DRIFT: %.%.% row % matches neither the manifest before nor the manifest after value',
        r.sch, r.tbl, r.col, r.rid;
    end if;
  end loop;

  -- End state: whatever the plan contained, every target now holds its repaired value. A plan that
  -- repaired a row and then wrote it back to a damaged value dies here, before commit.
  for r in select * from _mojibake_plan order by ord loop
    execute format('select %I from %I.%I where %I = $1', r.col, r.sch, r.tbl, r.pkcol)
      into cur using r.rid;
    if cur is distinct from r.val_after then
      raise exception 'END STATE: %.% row % holds % after the plan, expected %',
        r.tbl, r.col, r.rid, cur, r.val_after;
    end if;
  end loop;
end
$mojibake$;
select coalesce(json_agg(json_build_object(
  'rid', rid, 'schema', sch, 'table', tbl, 'column', col, 'outcome', outcome)), '[]')
from _mojibake_result;
commit;
`;

/**
 * Fast pre-flight: shapes the plan and refuses what it can already prove wrong, with a message an
 * operator can act on. It is not the gate -- EXECUTE_SQL is.
 */
function planFromDatabase(opts, rows, catalog) {
  const seen = new Set();
  const shaped = rows.map((r, i) => {
    for (const k of ['table', 'column', 'pk_column', 'rid', 'before', 'after']) {
      if (r[k] === undefined || r[k] === null) fail(`manifest row ${i} is missing ${k}`);
    }
    const sch = r.schema || 'public';
    // One target, one instruction. Two rows for the same physical value could only disagree, and a
    // pair that repairs then re-damages would each look reasonable on its own.
    const target = `${sch}.${r.table}.${r.column}:${r.rid}`;
    if (seen.has(target)) fail(`manifest names the same target twice: ${target}`);
    seen.add(target);
    return {
      ord: i, sch, tbl: r.table, col: r.column, pkcol: r.pk_column, rid: r.rid,
      tscol: SERVER_SET_TIMESTAMP[r.table] || null,
      val_before: r.before, val_after: r.after,
    };
  });

  for (const p of shaped) {
    if (!REPAIR_SCOPE.includes(`${p.tbl}.${p.col}`)) {
      fail(`manifest row ${p.ord} names ${p.tbl}.${p.col}, which this tool may never write. `
        + `Writable columns: ${REPAIR_SCOPE.join(', ')}.`);
    }
    if (!catalog.some((c) => c.sch === p.sch && c.tbl === p.tbl && c.col === p.col && c.pkcol === p.pkcol)) {
      fail(`manifest names ${p.sch}.${p.tbl}.${p.col} (pk ${p.pkcol}), which this database does not have`);
    }
    // Whatever the current value turns out to be, the pair itself must describe a repair: `after`
    // has to be the reversal of `before`. This is what stops a clean -> damaged row.
    const pair = classifyValue(p.val_before);
    if (!pair.suspect || pair.reversed !== p.val_after) {
      fail(`manifest row ${p.ord} (${p.tbl}.${p.col} ${p.rid}): 'after' is not the reversal of 'before'`);
    }
  }

  const current = currentValues(opts.conn, shaped);
  const evidence = gatherEvidence(opts.conn, shaped, catalog);
  for (const p of shaped) {
    const row = current.get(`${p.tbl}:${p.rid}`);
    if (!row) fail(`DRIFT: ${p.tbl}.${p.col} row ${p.rid} is gone`, 3);
    const ev = evidence.get(`${p.tbl}:${p.rid}`) || { first: null };

    if (row.val === p.val_after) continue; // already repaired; the transaction confirms it again
    if (row.val !== p.val_before) {
      fail(`DRIFT: ${p.tbl}.${p.col} row ${p.rid} matches neither the manifest before nor after value`, 3);
    }

    const a = assess({
      table: p.tbl, column: p.col, value: row.val, length: row.len,
      firstEvidence: ev.first, cutoff: opts.cutoff,
    });
    if (!a || !a.eligible) {
      fail(`manifest row ${p.ord} (${p.tbl}.${p.col} ${p.rid}) is not eligible against this database`
        + ` [${(a?.flags || ['not_damaged']).join(' ')}]; a manifest does not override that.`);
    }
    if (a.reversed !== p.val_after) {
      fail(`manifest row ${p.ord} (${p.tbl}.${p.col} ${p.rid}): 'after' is not the reversal of the stored value`);
    }
    const blocking = a.flags.filter((f) => !OVERRIDABLE_FLAGS.has(f));
    if (blocking.length) {
      fail(`manifest row ${p.ord} (${p.tbl}.${p.col} ${p.rid}) carries non-overridable flag(s) [${blocking.join(' ')}]`);
    }
    if (a.flags.length && !opts.allowFlagged) {
      fail(`manifest row ${p.ord} (${p.tbl}.${p.col} ${p.rid}) carries flags [${a.flags.join(' ')}]:`
        + ' remove it from the manifest, or pass --allow-flagged to accept it.');
    }
  }
  return shaped;
}

function execute(opts) {
  const manifest = JSON.parse(fs.readFileSync(opts.manifest, 'utf8'));
  const rows = manifest.rows;
  if (!Array.isArray(rows)) fail('manifest has no rows array');
  if (rows.length === 0) fail('manifest is empty');

  const stamped = manifest.source || {};
  if (!stamped.system_identifier || !stamped.database) {
    fail('manifest carries no source cluster identity: re-run the dry run against the database you '
      + 'intend to repair, and use the manifest it produces.');
  }
  const identity = clusterIdentity(opts.conn);
  if (stamped.system_identifier !== identity.system_identifier
    || stamped.database !== identity.database) {
    fail(`manifest was generated against database ${JSON.stringify(stamped.database)} on cluster `
      + `${stamped.system_identifier}, but this connection is database `
      + `${JSON.stringify(identity.database)} on cluster ${identity.system_identifier}. `
      + 'Re-run the dry run against the target itself.');
  }

  const catalog = query(opts.conn, CATALOG_SQL);
  const plan = planFromDatabase(opts, rows, catalog);

  const outDir = resolveOutDir(opts);
  const logFile = uniquePath(outDir, 'mojibake-repair-', '.log');
  const res = withTempSql(EXECUTE_SQL, (file) => runPsql(opts.conn, {
    file,
    vars: {
      plan: JSON.stringify(plan),
      // The manifest's own identity, so the transaction re-checks the binding rather than trusting
      // the pre-flight's separate connection.
      sysid: stamped.system_identifier,
      db: stamped.database,
      cutoff: opts.cutoff,
    },
  }));

  if (res.status !== 0) {
    const detail = res.stderr.trim();
    writePrivate(logFile, `${new Date().toISOString()}\tABORTED\n${detail}\n`);
    console.error(`\nrepair ABORTED, transaction rolled back, no row changed.\n${detail}`);
    console.error(`log: ${logFile}`);
    process.exit(3);
  }

  const outcomes = jsonPayload(res.stdout);
  const byRid = new Map(rows.map((r) => [r.rid, r]));
  const lines = outcomes.map((o) => {
    const r = byRid.get(o.rid);
    return [new Date().toISOString(), o.outcome, `${o.table}.${o.column}`, o.rid,
      JSON.stringify(r.before), JSON.stringify(r.after)].join('\t');
  });
  writePrivate(logFile, `${lines.join('\n')}\n`);

  const repaired = outcomes.filter((o) => o.outcome === 'REPAIRED').length;
  const noop = outcomes.filter((o) => o.outcome === 'NOOP').length;
  for (const o of outcomes) {
    const r = byRid.get(o.rid);
    console.log(`${o.outcome.padEnd(8)} ${o.table}.${o.column} ${o.rid}`);
    if (o.outcome === 'REPAIRED') console.log(`         ${JSON.stringify(r.before)} -> ${JSON.stringify(r.after)}`);
  }
  console.log(`\nrepaired ${repaired}, already-repaired no-op ${noop}, of ${plan.length} manifest rows`);
  console.log(`log: ${logFile}`);
  return 0;
}

function main() {
  const opts = parseArgs(process.argv.slice(2));
  process.exit(opts.execute ? execute(opts) : dryRun(opts));
}

if (import.meta.url === `file://${process.argv[1]}`) main();
