#!/usr/bin/env python3
# Dismiss the dispositioned CodeQL code-scanning alerts in GitHub so the Security tab
# agrees with the in-repo triage baseline (.github/baselines/codeql-known.json).
#
# The baseline is the source of truth the CI gate (scripts/codeql-gate.py) enforces,
# but dispositioning an alert there does NOT change its state in GitHub: every triaged
# alert stays "open" in the Security -> Code scanning tab. That makes a genuinely new
# finding hard to spot in a list of already-known ones. This tool closes that gap by
# dismissing each ALREADY-TRIAGED alert in GitHub, deriving the dismissal reason and
# comment from the matching baseline entry.
#
# Safety model (why this refuses rather than dismisses broadly):
#   * It dismisses an open alert ONLY when the alert's identity
#     (<ruleId>@<uri>:<startLine>:<startColumn>, the same scheme codeql-gate.py keys on)
#     is present in the baseline. An open alert whose identity is NOT in the baseline is
#     REFUSED (never dismissed) and reported — that alert is exactly the untriaged
#     finding a person needs to see, so the tool must not hide it.
#   * The dismissal reason must be derivable from the baseline entry's own `reason`
#     text. An entry whose reason maps to no GitHub dismissal category is reported and
#     left alone rather than dismissed with a guessed category.
#
# --dry-run is the DEFAULT: the tool prints the reason mapping and the planned
# dismissals and writes nothing. Pass --apply to perform the PATCH calls.
#
# Usage:
#   python3 scripts/dismiss-codeql-alerts.py [--baseline <path>] [--repo <owner/repo>] [--apply]
#
# Requires the `gh` CLI, authenticated, with permission to read and (for --apply) write
# code-scanning alerts on the repository.
#
# Exit codes:
#   0  every open alert matched a baseline entry; nothing was refused.
#   1  at least one open alert was refused (its identity is not in the baseline) — an
#      untriaged finding is present. Matched alerts are still reported/dismissed.
#   2  a structural or precondition failure (bad baseline, gh error, unmappable reason).

import argparse
import json
import subprocess

# GitHub's code-scanning API accepts exactly these dismissal categories.
VALID_DISMISSED_REASONS = ("false positive", "won't fix", "used in tests")

# GitHub caps the dismissal comment length; keep comments within it.
MAX_COMMENT_LENGTH = 280


def run_gh(args, *, allow_fail=False):
    """Invoke the gh CLI and return stdout. Raise SystemExit(2) on failure."""
    try:
        completed = subprocess.run(
            ["gh", *args],
            check=False,
            capture_output=True,
            text=True,
        )
    except FileNotFoundError:
        raise SystemExit("gh CLI not found on PATH — install and authenticate it first")
    if completed.returncode != 0:
        if allow_fail:
            return None
        detail = completed.stderr.strip() or completed.stdout.strip()
        raise SystemExit(f"gh {' '.join(args)} failed: {detail}")
    return completed.stdout


def resolve_repo(explicit):
    if explicit:
        return explicit
    out = run_gh(["repo", "view", "--json", "nameWithOwner", "-q", ".nameWithOwner"])
    repo = out.strip()
    if "/" not in repo:
        raise SystemExit("Could not resolve the repository; pass --repo <owner/repo>")
    return repo


def fetch_open_alerts(repo):
    """Return every open code-scanning alert, paginating explicitly (version-agnostic)."""
    alerts = []
    page = 1
    per_page = 100
    while True:
        out = run_gh([
            "api",
            f"repos/{repo}/code-scanning/alerts?state=open&per_page={per_page}&page={page}",
        ])
        batch = json.loads(out)
        if not isinstance(batch, list):
            raise SystemExit("Unexpected code-scanning API response (not a list)")
        alerts.extend(batch)
        if len(batch) < per_page:
            break
        page += 1
    return alerts


def alert_identity(alert):
    """Compute <ruleId>@<uri>:<startLine>:<startColumn> for an alert, matching the gate."""
    rule_id = (alert.get("rule") or {}).get("id", "unknown-rule")
    location = (alert.get("most_recent_instance") or {}).get("location") or {}
    uri = location.get("path", "unknown-file")
    start_line = location.get("start_line", 0)
    start_column = location.get("start_column", 1)
    return f"{rule_id}@{uri}:{start_line}:{start_column}"


def derive_reason(reason_text):
    """Map a baseline entry's `reason` prose to a GitHub dismissal category, or None.

    The mapping is keyword-driven so it derives from the recorded disposition rather than
    a per-entry hardcoding. It returns None when the prose matches no category, so the
    caller refuses instead of dismissing with a guessed reason.
    """
    text = (reason_text or "").lower()
    if "used in test" in text or "test-only" in text or "test fixture" in text:
        return "used in tests"
    if "won't fix" in text or "wont fix" in text or "will not fix" in text or "accepted risk" in text:
        return "won't fix"
    if (
        "already-mitigated" in text
        or "already mitigated" in text
        or "neutralizes" in text
        or "not exploitable" in text
        or "false positive" in text
        or "not reachable" in text
    ):
        return "false positive"
    return None


def build_comment(reason_text):
    comment = " ".join((reason_text or "").split())
    if len(comment) > MAX_COMMENT_LENGTH:
        comment = comment[: MAX_COMMENT_LENGTH - 1].rstrip() + "…"
    return comment


def load_baseline(path):
    try:
        baseline = json.loads(open(path, encoding="utf-8").read())
    except OSError as error:
        raise SystemExit(f"Cannot read baseline {path}: {error}")
    except json.JSONDecodeError as error:
        raise SystemExit(f"Baseline {path} is not valid JSON: {error}")
    findings = baseline.get("findings")
    if not isinstance(findings, list) or not findings:
        raise SystemExit("Baseline findings must be a non-empty array")
    by_identity = {}
    for index, entry in enumerate(findings):
        identity = entry.get("id")
        if not identity:
            raise SystemExit(f"baseline finding {index} lacks an id")
        if "reason" not in entry:
            raise SystemExit(f"baseline finding {identity} lacks a reason")
        by_identity[identity] = entry
    return by_identity


def plural(count, singular="y", many="ies"):
    return singular if count == 1 else many


def main():
    parser = argparse.ArgumentParser(
        description="Dismiss dispositioned CodeQL alerts in GitHub from the triage baseline",
    )
    parser.add_argument(
        "--baseline",
        default=".github/baselines/codeql-known.json",
        help="Path to the dispositioned-findings baseline JSON",
    )
    parser.add_argument(
        "--repo",
        default=None,
        help="owner/repo to act on (default: resolve from the current git remote)",
    )
    parser.add_argument(
        "--apply",
        action="store_true",
        help="Perform the dismissals. Without this flag the tool only reports (dry-run).",
    )
    args = parser.parse_args()
    dry_run = not args.apply

    by_identity = load_baseline(args.baseline)

    # Reason mapping: derive a dismissal category for EVERY baseline entry up front.
    # An entry whose reason maps to nothing is a precondition failure (exit 2).
    print(f"Reason mapping ({len(by_identity)} baseline entr{plural(len(by_identity))}):")
    reason_for = {}
    unmappable = []
    for identity, entry in by_identity.items():
        reason = derive_reason(entry.get("reason"))
        reason_for[identity] = reason
        print(f"  [{reason if reason else 'UNMAPPABLE'}] {identity}")
        if reason is None:
            unmappable.append(identity)
    if unmappable:
        print()
        print("ERROR — these baseline entries have no derivable dismissal reason:")
        for identity in unmappable:
            print(f"  x {identity}")
        raise SystemExit(2)

    repo = resolve_repo(args.repo)
    print()
    print(f"Repository: {repo}")
    print(f"Mode: {'DRY-RUN (no changes; pass --apply to dismiss)' if dry_run else 'APPLY (dismissing alerts)'}")

    alerts = fetch_open_alerts(repo)
    print(f"Open code-scanning alerts: {len(alerts)}")
    print()

    matched = []   # (number, identity, reason, comment)
    refused = []   # (number, identity)
    for alert in alerts:
        identity = alert_identity(alert)
        number = alert.get("number")
        if identity in by_identity:
            matched.append((number, identity, reason_for[identity], build_comment(by_identity[identity].get("reason"))))
        else:
            refused.append((number, identity))

    matched_identities = {identity for _, identity, _, _ in matched}
    no_alert = [identity for identity in by_identity if identity not in matched_identities]

    # Refused: open alerts not present in the baseline. These are never dismissed.
    if refused:
        print(f"REFUSED — {len(refused)} open alert(s) NOT in the baseline (left open, not dismissed):")
        for number, identity in refused:
            print(f"  x alert #{number}: {identity}")
        print()

    # Baseline entries with no matching open alert (already dismissed, or drifted).
    if no_alert:
        print(f"NO OPEN ALERT — {len(no_alert)} baseline entr{plural(len(no_alert))} with no matching open alert (already dismissed or coordinate-drifted):")
        for identity in no_alert:
            print(f"  - {identity}")
        print()

    # Matched: dismiss (apply) or report the planned dismissal (dry-run).
    print(f"{'WOULD DISMISS' if dry_run else 'DISMISSING'} — {len(matched)} matched alert(s):")
    for number, identity, reason, comment in matched:
        print(f"  alert #{number}  [{reason}]  {identity}")
        if not dry_run:
            run_gh([
                "api",
                "--method", "PATCH",
                f"repos/{repo}/code-scanning/alerts/{number}",
                "-f", "state=dismissed",
                "-f", f"dismissed_reason={reason}",
                "-f", f"dismissed_comment={comment}",
            ])
    print()

    print(
        f"Summary: {len(matched)} {'to dismiss' if dry_run else 'dismissed'}, "
        f"{len(refused)} refused (not in baseline), "
        f"{len(no_alert)} baseline entr{plural(len(no_alert))} with no open alert."
    )

    if refused:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
