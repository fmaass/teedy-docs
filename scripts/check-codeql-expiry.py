#!/usr/bin/env python3
# Surface the CodeQL triage-baseline re-triage deadline as planned work BEFORE it reds
# a build. Each entry in .github/baselines/codeql-known.json carries an `expires` date,
# and scripts/codeql-gate.py fails the build the moment any entry is past it. That is a
# deliberate forcing function for periodic re-review, but with no lead time it surfaces
# as a surprise red build. This checker reports the earliest expiry and warns while it
# is inside a lead window, so the re-triage can be scheduled rather than tripped over.
#
# It is a REMINDER, not a gate: it never exits nonzero for an approaching or past
# expiry (only for a structural problem in the baseline). The scheduled workflow that
# runs it turns a warn/expired status into a GitHub annotation and a tracking issue.
#
# Usage:
#   python3 scripts/check-codeql-expiry.py [--baseline <path>] [--lead-days N] [--today YYYY-MM-DD]
#
#   --lead-days   how many days before the earliest expiry to start warning (default 45).
#   --today       evaluate against this date instead of the system date (for testing).
#
# When run under GitHub Actions (the GITHUB_OUTPUT env var is set) it writes
# `status` (ok|warn|expired), `days_remaining`, `min_expiry`, and `summary` outputs.
#
# Exit codes:
#   0  status determined (ok, warn, or expired) — always, so it cannot fail a build.
#   2  a structural failure (unreadable baseline, missing or invalid expires date).

import argparse
import datetime
import json
import os
import sys
from pathlib import Path


def die(message):
    """Report a structural failure and exit 2 (distinct from the always-0 status path)."""
    print(message, file=sys.stderr)
    raise SystemExit(2)


def load_min_expiry(baseline_path):
    try:
        baseline = json.loads(Path(baseline_path).read_text())
    except OSError as error:
        die(f"Cannot read baseline {baseline_path}: {error}")
    except json.JSONDecodeError as error:
        die(f"Baseline {baseline_path} is not valid JSON: {error}")
    findings = baseline.get("findings")
    if not isinstance(findings, list) or not findings:
        die("Baseline findings must be a non-empty array")
    expiries = []
    for index, entry in enumerate(findings):
        raw = entry.get("expires")
        if not raw:
            die(f"baseline finding {index} lacks an expires date")
        try:
            expiries.append(datetime.date.fromisoformat(raw))
        except (TypeError, ValueError) as error:
            die(f"baseline finding {entry.get('id', index)} has invalid expires date: {error}")
    return min(expiries)


def emit_output(name, value):
    output_path = os.environ.get("GITHUB_OUTPUT")
    if output_path:
        with open(output_path, "a", encoding="utf-8") as handle:
            handle.write(f"{name}={value}\n")


def main():
    parser = argparse.ArgumentParser(
        description="Warn as the CodeQL triage baseline nears its re-triage deadline",
    )
    parser.add_argument(
        "--baseline",
        default=".github/baselines/codeql-known.json",
        help="Path to the dispositioned-findings baseline JSON",
    )
    parser.add_argument(
        "--lead-days",
        type=int,
        default=45,
        help="Start warning this many days before the earliest expiry (default 45)",
    )
    parser.add_argument(
        "--today",
        default=None,
        help="Evaluate against this ISO date instead of today (for testing)",
    )
    args = parser.parse_args()

    if args.lead_days < 0:
        die("--lead-days must not be negative")

    today = datetime.date.today()
    if args.today:
        try:
            today = datetime.date.fromisoformat(args.today)
        except ValueError as error:
            die(f"invalid --today date: {error}")

    min_expiry = load_min_expiry(args.baseline)
    days_remaining = (min_expiry - today).days

    if days_remaining < 0:
        status = "expired"
        summary = (
            f"CodeQL triage baseline EXPIRED on {min_expiry} "
            f"({-days_remaining} day(s) ago) — codeql-gate.py is failing until each entry is re-triaged."
        )
    elif days_remaining <= args.lead_days:
        status = "warn"
        summary = (
            f"CodeQL triage baseline expires on {min_expiry} in {days_remaining} day(s) "
            f"(lead window {args.lead_days} days) — schedule the re-triage before the gate reds."
        )
    else:
        status = "ok"
        summary = (
            f"CodeQL triage baseline expires on {min_expiry} in {days_remaining} day(s); "
            f"outside the {args.lead_days}-day lead window."
        )

    print(summary)
    # GitHub Actions annotation (harmless as plain output when run locally). A warning
    # annotation never fails the job on its own — only a nonzero exit would.
    if status in ("warn", "expired"):
        print(f"::warning title=CodeQL baseline re-triage due::{summary}")

    emit_output("status", status)
    emit_output("days_remaining", days_remaining)
    emit_output("min_expiry", min_expiry.isoformat())
    emit_output("summary", summary)


if __name__ == "__main__":
    main()
