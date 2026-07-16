#!/usr/bin/env node
// Verifies that the hand-authored OpenAPI spec at
// docs-web/src/main/webapp/public/apidoc/openapi.json documents every JAX-RS
// endpoint (and its declared parameters) exposed by the docs-web REST resources.
//
// Why this exists: issue #15 restores /apidoc as a BUILD-TIME static spec with NO
// runtime swagger scanner in the WAR. Without a scanner, nothing but this script
// keeps the hand-authored spec honest as the API evolves. It derives the expected
// surface from the resource SOURCES at runtime (never a hardcoded list) so a new
// endpoint or parameter that is not documented fails CI.
//
// Detection model:
//   * @Path classes  -> tag / base path
//   * verb methods   -> operations (path = class @Path + method @Path)
//   * @FormParam / @QueryParam / @PathParam / @FormDataParam names -> parameters
//     the script CAN see by scanning the method signature.
//   * MultivaluedMap<String, String> body -> parameters are read via
//     form.getFirst("...") in the method body and are INVISIBLE to a signature
//     scan. Those endpoints must appear in the hand-curated checklist below
//     (openapi-multivaluedmap-checklist.json) which lists their expected
//     documented parameter names; the script enforces both directions.
//
// Usage:
//   node scripts/check-openapi-parity.mjs            # verify (exit 1 on drift)
//   node scripts/check-openapi-parity.mjs --dump     # print derived surface as JSON
//
// Run from the repository root.

import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join, resolve } from 'node:path';

const scriptDir = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(scriptDir, '..');
// The packages Jersey scans for resources (web.xml jersey.config.server.provider.packages,
// mirrored by BaseJerseyTest and TestCsrfGetInventory — keep all four in sync): the legacy
// resource package plus the Phase G document-slice edge.
const resourceDirs = [
  join(repoRoot, 'docs-web/src/main/java/com/sismics/docs/rest/resource'),
  join(repoRoot, 'docs-web/src/main/java/com/sismics/docs/rest/document'),
];
const specPath = join(
  repoRoot,
  'docs-web/src/main/webapp/public/apidoc/openapi.json'
);
const checklistPath = join(scriptDir, 'openapi-multivaluedmap-checklist.json');

const HTTP_VERBS = ['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS'];

/**
 * Normalise a JAX-RS path fragment into an OpenAPI path template.
 * JAX-RS: {id: [a-z0-9\-]+}  or  {sourceId: [a-z0-9\-]+}/{perm: [A-Z]+}
 * OpenAPI: {id}, {sourceId}/{perm}
 */
function normalizePath(raw) {
  if (!raw) return '';
  // Strip the regex constraint inside each {name: regex} -> {name}
  const stripped = raw.replace(/\{\s*([a-zA-Z0-9_]+)\s*:[^}]*\}/g, '{$1}');
  return stripped;
}

function joinPaths(base, sub) {
  // JAX-RS treats @Path("document") and @Path("/document") identically; normalize to the
  // leading-slash form the OpenAPI spec uses.
  let a = base.replace(/\/+$/, '');
  if (a && !a.startsWith('/')) a = `/${a}`;
  const b = (sub || '').replace(/^\/+/, '');
  if (!b) return a || '/';
  return `${a}/${b}`;
}

/**
 * Strip Java line and block comments (approximately — string literals are left
 * intact, which is fine for our structural scans). Prevents the word "class" or a
 * stray "@Path" inside a comment from being mistaken for real code.
 */
function stripComments(source) {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/\/\/[^\n]*/g, ' ');
}

/**
 * Extract the class-level @Path value from a resource source, or null if the
 * type carries no class-level @Path (i.e. it is not a root JAX-RS resource).
 * "Class-level" = the @Path that sits on the type declaration, before/among the
 * type-declaration annotations — not a method-level @Path. The type keyword may
 * be class, interface, enum, or record.
 */
function extractClassPath(source) {
  const code = stripComments(source);
  const idx = code.search(/\b(?:public\s+|final\s+|abstract\s+)*(?:class|interface|enum|record)\s+\w/);
  const head = idx >= 0 ? code.slice(0, idx) : code;
  const m = head.match(/@Path\(\s*"([^"]*)"\s*\)/);
  return m ? m[1] : null;
}

/**
 * Parse one resource .java file into a list of endpoint descriptors.
 * Regex-based (no Java parser dependency) but tolerant of multi-line signatures.
 */
function parseResource(source, fileName) {
  const classPath = extractClassPath(source);
  if (classPath === null) return { classPath: null, endpoints: [] };

  const endpoints = [];
  const lines = source.split('\n');

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    const verbMatch = line.match(/^@(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\b/);
    if (!verbMatch) continue;
    const verb = verbMatch[1];

    // Look ahead over the annotation block + signature until the opening "{".
    let methodPath = '';
    const params = [];
    let usesMultivaluedMap = false;
    let usesFormData = false;
    let j = i + 1;
    let sigBuf = '';
    let sawSignature = false;
    let bodyStart = -1;
    for (; j < lines.length && j < i + 80; j++) {
      const t = lines[j];
      const trimmed = t.trim();

      const pm = trimmed.match(/^@Path\(\s*"([^"]*)"\s*\)/);
      if (pm) {
        methodPath = pm[1];
        continue;
      }

      sigBuf += ' ' + t;
      if (/public\s+\w/.test(t)) sawSignature = true;
      if (sawSignature && t.includes('{')) {
        bodyStart = j;
        break;
      }
    }

    // Extract parameter names from the collected signature buffer.
    const paramRe = /@(FormParam|QueryParam|PathParam|FormDataParam|HeaderParam)\(\s*"([^"]+)"\s*\)/g;
    let pmatch;
    while ((pmatch = paramRe.exec(sigBuf)) !== null) {
      params.push({ kind: pmatch[1], name: pmatch[2] });
    }
    if (/MultivaluedMap\s*<\s*String\s*,\s*String\s*>/.test(sigBuf)) {
      usesMultivaluedMap = true;
    }
    if (/@FormDataParam/.test(sigBuf)) {
      usesFormData = true;
    }

    // For MultivaluedMap endpoints the form fields are read in the method BODY via
    // form.getFirst("key") / form.get("key"). Those keys are invisible to a
    // signature scan, so harvest them here (brace-matched over the method body) to
    // enforce that every one is covered by the checklist.
    const formKeys = [];
    if (usesMultivaluedMap && bodyStart >= 0) {
      const body = extractMethodBody(lines, bodyStart);
      const keyRe = /\bform\s*\.\s*(?:getFirst|get)\s*\(\s*"([^"]+)"\s*\)/g;
      let km;
      const seenKey = new Set();
      while ((km = keyRe.exec(body)) !== null) {
        if (!seenKey.has(km[1])) {
          seenKey.add(km[1]);
          formKeys.push(km[1]);
        }
      }
    }

    const fullPath = normalizePath(joinPaths(classPath, methodPath));
    endpoints.push({
      file: fileName,
      classPath,
      method: verb.toLowerCase(),
      path: fullPath,
      params,
      usesMultivaluedMap,
      usesFormData,
      formKeys,
    });
  }

  return { classPath, endpoints };
}

/**
 * Return the source text of a method body, from the line carrying the opening
 * "{" through the matching closing "}", tracking brace depth. String and char
 * literals are only approximately handled — good enough to harvest form.get keys.
 */
function extractMethodBody(lines, bodyStart) {
  let depth = 0;
  let started = false;
  const collected = [];
  for (let k = bodyStart; k < lines.length; k++) {
    const line = lines[k];
    collected.push(line);
    for (const ch of line) {
      if (ch === '{') {
        depth++;
        started = true;
      } else if (ch === '}') {
        depth--;
      }
    }
    if (started && depth <= 0) break;
  }
  return collected.join('\n');
}

function loadSurface() {
  // Discover JAX-RS resources by CONTENT, not filename: any .java in a scanned package
  // that carries a class-level @Path is a root resource, regardless of its name (a future
  // Health.java without the *Resource suffix must still be caught). The directory set
  // mirrors Jersey's provider.packages scan config (see resourceDirs above), so both the
  // legacy resource package and the document-slice edge are authoritative sources.
  // BaseResource (abstract, no class-level @Path) is excluded intrinsically by the @Path
  // filter — no name-based exclusion needed.
  const all = [];
  const classPaths = new Map();
  for (const dir of resourceDirs) {
    const files = readdirSync(dir)
      .filter((f) => f.endsWith('.java'))
      .sort();
    for (const f of files) {
      const source = readFileSync(join(dir, f), 'utf8');
      if (extractClassPath(source) === null) continue; // not a root JAX-RS resource
      const parsed = parseResource(source, f);
      classPaths.set(f, parsed.classPath);
      for (const ep of parsed.endpoints) all.push(ep);
    }
  }
  return { endpoints: all, classPaths };
}

function loadSpec() {
  let raw;
  try {
    raw = readFileSync(specPath, 'utf8');
  } catch (e) {
    fail(`Cannot read spec at ${specPath}: ${e.message}`);
  }
  let spec;
  try {
    spec = JSON.parse(raw);
  } catch (e) {
    fail(`Spec is not valid JSON: ${e.message}`);
  }
  return spec;
}

const errors = [];
function fail(msg) {
  console.error(`✖ ${msg}`);
  errors.push(msg);
}

function specHasOperation(spec, path, method) {
  const item = spec.paths && spec.paths[path];
  if (!item) return null;
  return item[method] || null;
}

/**
 * Collect all documented parameter names for an operation:
 *  - path/query parameters (in the "parameters" array)
 *  - form fields declared in requestBody schema (urlencoded or multipart)
 */
function documentedParamNames(op) {
  const names = new Set();
  if (Array.isArray(op.parameters)) {
    for (const p of op.parameters) {
      if (p && p.name) names.add(p.name);
    }
  }
  const content =
    op.requestBody && op.requestBody.content ? op.requestBody.content : {};
  for (const mediaType of Object.keys(content)) {
    const schema = content[mediaType] && content[mediaType].schema;
    if (schema && schema.properties) {
      for (const prop of Object.keys(schema.properties)) names.add(prop);
    }
  }
  return names;
}

function main() {
  const dump = process.argv.includes('--dump');
  const { endpoints } = loadSurface();

  if (dump) {
    console.log(JSON.stringify(endpoints, null, 2));
    return;
  }

  const spec = loadSpec();
  const checklist = JSON.parse(readFileSync(checklistPath, 'utf8'));

  // 1. Every derived endpoint is present with all its VISIBLE parameters.
  let checkedParams = 0;
  for (const ep of endpoints) {
    const op = specHasOperation(spec, ep.path, ep.method);
    if (!op) {
      fail(
        `Missing operation in spec: ${ep.method.toUpperCase()} ${ep.path} (from ${ep.file})`
      );
      continue;
    }
    const documented = documentedParamNames(op);
    for (const p of ep.params) {
      checkedParams++;
      if (!documented.has(p.name)) {
        fail(
          `Missing parameter "${p.name}" (${p.kind}) on ${ep.method.toUpperCase()} ${ep.path} in spec (from ${ep.file})`
        );
      }
    }
  }

  // 2. MultivaluedMap endpoints: parameters are invisible to a signature scan,
  //    so they MUST be covered by the hand-curated checklist, and the spec must
  //    document every checklist parameter for them.
  const mvmEndpoints = endpoints.filter((e) => e.usesMultivaluedMap);
  const checklistKeys = new Set(Object.keys(checklist.endpoints || {}));
  let checkedFormKeys = 0;

  for (const ep of mvmEndpoints) {
    const key = `${ep.method.toUpperCase()} ${ep.path}`;
    if (!checklistKeys.has(key)) {
      fail(
        `MultivaluedMap endpoint ${key} (from ${ep.file}) is not in the checklist ${'scripts/openapi-multivaluedmap-checklist.json'}. Add it with its expected documented parameter names.`
      );
      continue;
    }
    const expected = checklist.endpoints[key];

    // 2a. Source-derived completeness: every form.getFirst("x")/form.get("x") key
    //     read in the method body MUST be in the checklist. Without this, a newly
    //     added form key could be omitted from BOTH checklist and spec and still
    //     pass CI. checkedFormKeys is the source-of-truth count.
    const expectedSet = new Set(expected);
    for (const k of ep.formKeys) {
      checkedFormKeys++;
      if (!expectedSet.has(k)) {
        fail(
          `MultivaluedMap endpoint ${key}: form key "${k}" is read via form.get(First) in ${ep.file} but is not listed in the checklist ${'scripts/openapi-multivaluedmap-checklist.json'}. Add it (and document it in the spec).`
        );
      }
    }

    const op = specHasOperation(spec, ep.path, ep.method);
    if (!op) continue; // already reported above
    const documented = documentedParamNames(op);
    for (const name of expected) {
      if (!documented.has(name)) {
        fail(
          `MultivaluedMap endpoint ${key}: checklist parameter "${name}" is not documented in the spec.`
        );
      }
    }
  }

  // 3. Reverse guard: no stale checklist entries for endpoints that no longer
  //    read a MultivaluedMap body (keeps the checklist honest).
  const mvmKeys = new Set(
    mvmEndpoints.map((e) => `${e.method.toUpperCase()} ${e.path}`)
  );
  for (const key of checklistKeys) {
    if (!mvmKeys.has(key)) {
      fail(
        `Stale checklist entry "${key}" — no MultivaluedMap endpoint in the sources matches it. Remove it from the checklist.`
      );
    }
  }

  if (errors.length > 0) {
    console.error(
      `\nOpenAPI parity FAILED: ${errors.length} problem(s). ` +
        `The spec at docs-web/src/main/webapp/public/apidoc/openapi.json is out of sync with the REST resources.`
    );
    process.exit(1);
  }

  console.log(
    `OpenAPI parity OK: ${endpoints.length} endpoints, ${checkedParams} visible parameters, ` +
      `${mvmEndpoints.length} MultivaluedMap endpoint(s) checklisted (${checkedFormKeys} source form key(s) verified) — all documented.`
  );
}

main();
