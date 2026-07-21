#!/usr/bin/env python3
# Shared CodeQL high-severity gate. ONE source of truth for the "fail the build on
# a NEW high-severity CodeQL finding" logic that build-deploy.yml and regression.yml
# both invoke, so the two workflows cannot drift apart (they previously carried a
# byte-identical copy of this block each).
#
# Reads dispositioned findings from the baseline (default
# .github/baselines/codeql-known.json), validates their schema and non-expiry, then
# scans every *.sarif under the given SARIF directory and fails if a result with a
# security-severity >= 7.0 has an identity not present in the baseline.
#
# Every entry is REQUIRED to carry a `sink_line` fingerprint (the verbatim source line
# at its coordinate). This gate does not read it, but requiring it here means a new
# entry added without one fails loudly rather than being silently unchecked by the
# pre-push coordinate-drift gate (scripts/check-codeql-baseline-drift.mjs).
#
# Identity scheme (must stay in lockstep with scripts/refresh-codeql-baseline.mjs):
#   <ruleId>@<artifact uri>:<startLine>:<startColumn>
# One result = one identity. Location identities are injective per result, auditable
# against the GitHub code-scanning UI, and locally computable; the cost is that
# shifting a flagged line requires a conscious baseline update (automated by
# refresh-codeql-baseline.mjs).
#
# Usage:
#   python3 scripts/codeql-gate.py <sarif-dir> [--baseline <path>]
#
# Run from the repository root. Exit 0 = no new high finding; exit 1 = new finding;
# any other nonzero = a structural/precondition failure (SystemExit message).

import argparse
import datetime
import json
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="Gate new high-severity CodeQL findings")
    parser.add_argument("sarif_dir", help="Directory containing CodeQL *.sarif output")
    parser.add_argument(
        "--baseline",
        default=".github/baselines/codeql-known.json",
        help="Path to the dispositioned-findings baseline JSON",
    )
    args = parser.parse_args()

    required = {
        "id", "sink_line", "finding", "reason", "owner", "introduced", "expires",
        "compensating_control", "removal_issue",
    }
    baseline = json.loads(Path(args.baseline).read_text())
    if set(baseline.get("schema", {}).get("required", [])) != required:
        raise SystemExit("CodeQL baseline does not declare the required schema")
    findings = baseline.get("findings")
    if not isinstance(findings, list):
        raise SystemExit("CodeQL baseline findings must be an array")
    known = set()
    for index, finding in enumerate(findings):
        missing = required - set(finding)
        if missing:
            raise SystemExit(f"baseline finding {index} lacks fields: {sorted(missing)}")
        if not finding["owner"] or not finding["expires"]:
            raise SystemExit(f"baseline finding {finding['id']} lacks owner or expires")
        try:
            expiry = datetime.date.fromisoformat(finding["expires"])
        except ValueError as error:
            raise SystemExit(f"baseline finding {finding['id']} has invalid expires date: {error}")
        if expiry < datetime.date.today():
            raise SystemExit(f"baseline finding {finding['id']} expired on {expiry}")
        known.add(finding["id"])

    sarif_files = sorted(Path(args.sarif_dir).rglob("*.sarif"))
    if not sarif_files:
        raise SystemExit("CodeQL produced no SARIF files")
    new_high = []
    for sarif_file in sarif_files:
        try:
            raw_sarif = sarif_file.read_text()
        except OSError as error:
            raise SystemExit(f"Cannot read CodeQL SARIF {sarif_file}: {error}")
        if not raw_sarif.strip():
            raise SystemExit(f"CodeQL SARIF is empty: {sarif_file}")
        try:
            sarif = json.loads(raw_sarif)
        except json.JSONDecodeError as error:
            raise SystemExit(f"CodeQL SARIF is not valid JSON ({sarif_file}): {error}")
        if not isinstance(sarif, dict) or sarif.get("version") != "2.1.0":
            raise SystemExit(f"CodeQL output is not SARIF 2.1.0: {sarif_file}")
        runs = sarif.get("runs")
        if not isinstance(runs, list) or not runs:
            raise SystemExit(f"CodeQL SARIF has no analysis runs: {sarif_file}")
        for run_index, run in enumerate(runs):
            if not isinstance(run, dict):
                raise SystemExit(f"CodeQL SARIF run {run_index} is malformed: {sarif_file}")
            driver = run.get("tool", {}).get("driver")
            if not isinstance(driver, dict) or not driver.get("name"):
                raise SystemExit(f"CodeQL SARIF run {run_index} lacks a tool driver: {sarif_file}")
            results = run.get("results")
            if not isinstance(results, list):
                raise SystemExit(f"CodeQL SARIF run {run_index} lacks a results array: {sarif_file}")
            rules = {}
            components = [driver]
            components.extend(run.get("tool", {}).get("extensions", []))
            for component in components:
                for rule in component.get("rules", []):
                    rules[rule.get("id")] = rule
            for result_index, result in enumerate(results):
                if not isinstance(result, dict):
                    raise SystemExit(f"CodeQL SARIF result {result_index} is malformed: {sarif_file}")
                rule_id = result.get("ruleId", "unknown-rule")
                severity = rules.get(rule_id, {}).get("properties", {}).get("security-severity")
                try:
                    score = float(severity)
                except (TypeError, ValueError):
                    continue
                if score < 7.0:
                    continue
                # One result = one identity: rule id + physical location. The previous
                # scheme hashed CodeQL partialFingerprints (line-content hash + column
                # fingerprint), which collapses alerts on byte-identical lines in different
                # files into ONE identity (not multiplicity-safe) and cannot be recomputed
                # from the code-scanning API. Location identities are injective per result,
                # auditable against the GitHub code-scanning UI, and locally computable; the
                # cost is that shifting a flagged line requires a conscious baseline update.
                location = (result.get("locations") or [{}])[0].get("physicalLocation", {})
                uri = location.get("artifactLocation", {}).get("uri", "unknown-file")
                region = location.get("region", {})
                finding_id = f"{rule_id}@{uri}:{region.get('startLine', 0)}:{region.get('startColumn', 1)}"
                if finding_id not in known:
                    message = result.get("message", {}).get("text", "CodeQL finding")
                    new_high.append((finding_id, score, message))
    if new_high:
        for finding_id, score, message in new_high:
            print(f"NEW HIGH CODEQL FINDING {finding_id} ({score}): {message}")
        raise SystemExit(1)
    print("No new high-severity CodeQL findings")


if __name__ == "__main__":
    main()
